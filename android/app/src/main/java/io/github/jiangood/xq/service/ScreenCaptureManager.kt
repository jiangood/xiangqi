package io.github.jiangood.xq.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object ScreenCaptureManager {
    private const val SCREENSHOT_NAME = "floating_screenshot.png"
    private const val CAPTURE_TIMEOUT_SECONDS = 3

    fun capture(mediaProjection: MediaProjection, context: Context): File? {
        return try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            val virtualDisplay = mediaProjection.createVirtualDisplay(
                "FloatingCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface, null, null
            )

            val latch = CountDownLatch(1)
            var bitmap: Bitmap? = null
            var captureError: Exception? = null

            imageReader.setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        bitmap = imageToBitmap(image)
                        image.close()
                    }
                } catch (e: Exception) {
                    captureError = e
                } finally {
                    latch.countDown()
                }
            }, null)

            val success = latch.await(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            virtualDisplay.release()
            imageReader.close()

            if (!success || captureError != null) {
                if (captureError != null) {
                    android.util.Log.e("ScreenCapture", "Capture failed", captureError)
                }
                return null
            }

            val file = File(context.cacheDir, SCREENSHOT_NAME)
            bitmap?.let { bmp ->
                FileOutputStream(file).use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }
            if (file.exists()) file else null
        } catch (e: Exception) {
            android.util.Log.e("ScreenCapture", "Capture exception", e)
            null
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }
}
