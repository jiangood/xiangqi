package io.github.jiangood.xq.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object ScreenCaptureManager {
    private const val SCREENSHOT_NAME = "floating_screenshot.png"
    private const val CAPTURE_TIMEOUT_SECONDS = 5

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var ctx: Context? = null

    private var inited = false

    private val captureRequested = AtomicBoolean(false)
    private var captureLatch: CountDownLatch? = null
    private var capturedBitmap: Bitmap? = null

    fun isInitialized(): Boolean = inited

    fun init(mediaProjection: MediaProjection, context: Context): Boolean {
        release()
        ctx = context

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        AppLog.add("[截屏] 屏幕尺寸: ${width}x${height}, density=$density")

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        handlerThread = HandlerThread("ScreenCapture").apply { start() }
        handler = Handler(handlerThread!!.looper)

        imageReader?.setOnImageAvailableListener({ reader ->
            try {
                val image = reader.acquireNextImage()
                if (captureRequested.get()) {
                    capturedBitmap = imageToBitmap(image)
                    captureLatch?.countDown()
                }
                image.close()
            } catch (_: Exception) {
            }
        }, handler)

        virtualDisplay = try {
            mediaProjection.createVirtualDisplay(
                "FloatingCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )
        } catch (e: Exception) {
            AppLog.add("[截屏] VirtualDisplay 创建失败: ${e.message}")
            release()
            return false
        }

        inited = true
        AppLog.add("[截屏] ScreenCaptureManager 初始化完成")
        return true
    }

    fun capture(): File? {
        if (!inited) {
            AppLog.add("[截屏] ScreenCaptureManager 未初始化")
            return null
        }

        val latch = CountDownLatch(1)
        captureLatch = latch
        captureRequested.set(true)
        capturedBitmap = null

        try {
            if (!latch.await(CAPTURE_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)) {
                AppLog.add("[截屏] 截屏超时 (${CAPTURE_TIMEOUT_SECONDS}s)")
                return null
            }

            val bmp = capturedBitmap
            if (bmp == null) {
                AppLog.add("[截屏] bitmap 为空")
                return null
            }

            val file = File(ctx!!.cacheDir, SCREENSHOT_NAME)
            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            AppLog.add("[截屏] 截屏成功: ${file.name} (${file.length()} bytes)")
            return file
        } catch (e: Exception) {
            val msg = "截屏异常: ${e.message}"
            AppLog.add("[截屏] $msg")
            Log.e("ScreenCapture", "capture exception", e)
            return null
        } finally {
            captureRequested.set(false)
            captureLatch = null
            capturedBitmap = null
        }
    }

    fun release() {
        inited = false
        captureLatch?.countDown()
        captureLatch = null
        capturedBitmap = null
        captureRequested.set(false)

        virtualDisplay?.release()
        imageReader?.close()
        handlerThread?.quitSafely()
        virtualDisplay = null
        imageReader = null
        handlerThread = null
        handler = null
        ctx = null
        AppLog.add("[截屏] ScreenCaptureManager 已释放")
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
