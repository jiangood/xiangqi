package io.github.jiangood.xq.platform

import android.content.ContentResolver
import android.net.Uri
import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.InputStream

object AndroidImageUtils {
    fun bitmapToMat(bitmap: Bitmap): Mat {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        return mat
    }

    fun matToBitmap(mat: Mat): Bitmap {
        val rgba = Mat()
        Imgproc.cvtColor(mat, rgba, Imgproc.COLOR_BGR2RGBA)
        val bitmap = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bitmap)
        rgba.release()
        return bitmap
    }

    fun matToJpeg(mat: Mat, path: String, maxWidth: Int = 600, quality: Int = 85) {
        if (mat.empty()) return
        val src = mat
        val resized: Mat
        if (src.width() > maxWidth) {
            val scale = maxWidth.toDouble() / src.width()
            resized = Mat()
            Imgproc.resize(src, resized, Size(), scale, scale, Imgproc.INTER_AREA)
        } else {
            resized = src.clone()
        }
        val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, quality)
        Imgcodecs.imwrite(path, resized, params)
        resized.release()
    }

    fun copyToFile(input: InputStream, file: File) {
        file.outputStream().use { output -> input.copyTo(output) }
    }

    fun copyUriToFile(contentResolver: ContentResolver, uri: Uri, file: File) {
        contentResolver.openInputStream(uri)?.use { input ->
            copyToFile(input, file)
        }
    }

    fun cleanupOldAnalysisDirs(cacheDir: File, maxAgeMs: Long = 3600_000L) {
        val dirs = cacheDir.listFiles { f -> f.isDirectory && f.name.startsWith("analysis_") }
        if (dirs == null) return
        val now = System.currentTimeMillis()
        for (dir in dirs) {
            if (now - dir.lastModified() > maxAgeMs) {
                dir.deleteRecursively()
            }
        }
    }
}
