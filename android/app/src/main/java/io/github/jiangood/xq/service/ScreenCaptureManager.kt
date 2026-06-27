package io.github.jiangood.xq.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import io.github.jiangood.xq.util.AppLog
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object ScreenCaptureManager {
    private const val SCREENSHOT_NAME = "floating_screenshot.png"
    private const val CAPTURE_TIMEOUT_SECONDS = 5

    fun capture(mediaProjection: MediaProjection, context: Context): File? {
        var virtualDisplay: android.hardware.display.VirtualDisplay? = null
        var imageReader: ImageReader? = null
        var handlerThread: HandlerThread? = null
        return try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi
            AppLog.add("[截屏] 屏幕尺寸: ${width}x${height}, density=$density")

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)

            val latch = CountDownLatch(1)
            var bitmap: Bitmap? = null
            var callbackError: String? = null

            handlerThread = HandlerThread("ScreenCapture")
            handlerThread.start()
            imageReader.setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireNextImage()
                    AppLog.add("[截屏] ImageReader 收到图像: ${image.width}x${image.height}, " +
                            "format=${image.format}, planes=${image.planes.size}")
                    bitmap = imageToBitmap(image)
                    image.close()
                } catch (e: Exception) {
                    callbackError = "图像回调异常: ${e.message}"
                    Log.e("ScreenCapture", "image callback error", e)
                } finally {
                    latch.countDown()
                }
            }, Handler(handlerThread.looper))

            AppLog.add("[截屏] 创建 VirtualDisplay...")
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "FloatingCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface, null, null
            )
            AppLog.add("[截屏] VirtualDisplay 已创建")

            if (!latch.await(CAPTURE_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)) {
                val msg = "截屏超时 (${CAPTURE_TIMEOUT_SECONDS}s)"
                Log.e("ScreenCapture", msg)
                AppLog.add("[截屏] $msg")
                return null
            }

            if (bitmap == null) {
                val msg = callbackError ?: "bitmap 为空"
                Log.e("ScreenCapture", msg)
                AppLog.add("[截屏] $msg")
                return null
            }

            AppLog.add("[截屏] 截屏成功, bitmap=${bitmap.width}x${bitmap.height}")
            val file = File(context.cacheDir, SCREENSHOT_NAME)
            FileOutputStream(file).use { out ->
                bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            AppLog.add("[截屏] 已保存: ${file.absolutePath} (${file.length()} bytes)")
            file
        } catch (e: Exception) {
            val msg = "截屏异常: ${e.message}"
            Log.e("ScreenCapture", "capture exception", e)
            AppLog.add("[截屏] $msg")
            null
        } finally {
            virtualDisplay?.release()
            imageReader?.close()
            handlerThread?.quitSafely()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height

        if (pixelStride == 4 && rowStride == width * 4) {
            buffer.rewind()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            return bitmap
        }

        // 处理 stride 不对齐的情况
        val pixels = IntArray(width * height)
        val row = ByteArray(rowStride)
        for (y in 0 until height) {
            buffer.position(y * rowStride)
            buffer.get(row, 0, rowStride)
            for (x in 0 until width) {
                val i = x * pixelStride
                val r = row[i].toInt() and 0xFF
                val g = row[i + 1].toInt() and 0xFF
                val b = row[i + 2].toInt() and 0xFF
                val a = row[i + 3].toInt() and 0xFF
                pixels[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
