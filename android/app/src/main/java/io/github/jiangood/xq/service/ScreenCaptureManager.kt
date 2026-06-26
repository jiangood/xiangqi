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
import java.io.File
import java.io.FileOutputStream
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

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

            val latch = CountDownLatch(1)
            var bitmap: Bitmap? = null

            handlerThread = HandlerThread("ScreenCapture")
            handlerThread.start()
            imageReader.setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireNextImage()
                    bitmap = imageToBitmap(image)
                    image.close()
                } catch (e: Exception) {
                    Log.e("ScreenCapture", "image callback error", e)
                } finally {
                    latch.countDown()
                }
            }, Handler(handlerThread.looper))

            virtualDisplay = mediaProjection.createVirtualDisplay(
                "FloatingCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface, null, null
            )

            if (!latch.await(CAPTURE_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)) {
                Log.e("ScreenCapture", "capture timeout after ${CAPTURE_TIMEOUT_SECONDS}s")
                return null
            }

            if (bitmap == null) {
                Log.e("ScreenCapture", "bitmap is null")
                return null
            }

            val file = File(context.cacheDir, SCREENSHOT_NAME)
            FileOutputStream(file).use { out ->
                bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file
        } catch (e: Exception) {
            Log.e("ScreenCapture", "capture exception", e)
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
        buffer.rewind()
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }
}
