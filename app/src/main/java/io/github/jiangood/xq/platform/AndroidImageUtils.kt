package io.github.jiangood.xq.platform

import android.content.ContentResolver
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import io.github.jiangood.xq.opencv.BoardUtils
import io.github.jiangood.xq.util.FenUtil
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.Point
import org.opencv.core.Scalar
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

    fun drawPieceLabels(canvas: Canvas, grid: Array<Array<Point>>, board: Array<Array<String?>>) {
        val redPaint = Paint().apply {
            color = android.graphics.Color.RED
            textSize = 28f
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
        }
        val bluePaint = Paint().apply {
            color = 0xFF1976D2.toInt()
            textSize = 28f
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
        }
        for (r in 0 until 10) {
            for (c in 0 until 9) {
                val p = board[r][c] ?: continue
                val ch = FenUtil.PIECE_CHINESE[p] ?: continue
                val pt = grid[r][c]
                val paint = if (p.startsWith("r")) redPaint else bluePaint
                val bgPaint = Paint().apply {
                    color = android.graphics.Color.argb(180, 255, 255, 255)
                }
                val textW = paint.measureText(ch)
                canvas.drawRect(
                    (pt.x - textW / 2 - 2).toFloat(),
                    (pt.y - paint.textSize / 2 - 2).toFloat(),
                    (pt.x + textW / 2 + 2).toFloat(),
                    (pt.y + paint.textSize / 2 + 2).toFloat(),
                    bgPaint
                )
                canvas.drawText(ch, (pt.x - textW / 2).toFloat(), (pt.y + paint.textSize / 3).toFloat(), paint)
            }
        }
    }

    fun renderBoardVisualization(
        imagePath: String,
        grid: Array<Array<Point>>,
        board: Array<Array<String?>>,
        bestMove: String? = null
    ): Bitmap? {
        val img = Imgcodecs.imread(imagePath, Imgcodecs.IMREAD_COLOR) ?: return null
        val cropped = BoardUtils.cropBoardCenter(img)
        img.release()
        val mat = cropped

        val green = Scalar(0.0, 255.0, 0.0)
        for (r in 0 until 10) {
            Imgproc.line(mat, Point(grid[r][0].x, grid[r][0].y), Point(grid[r][8].x, grid[r][8].y), green, 2)
        }
        for (c in 0 until 9) {
            Imgproc.line(mat, Point(grid[0][c].x, grid[0][c].y), Point(grid[9][c].x, grid[9][c].y), green, 2)
        }

        if (bestMove != null && bestMove.length == 4) {
            val fromCol = bestMove[0] - 'a'
            val fromRow = 9 - (bestMove[1] - '0')
            val toCol = bestMove[2] - 'a'
            val toRow = 9 - (bestMove[3] - '0')
            if (fromCol in 0..8 && fromRow in 0..9 && toCol in 0..8 && toRow in 0..9) {
                val yellow = Scalar(0.0, 255.0, 255.0)
                Imgproc.arrowedLine(mat, grid[fromRow][fromCol], grid[toRow][toCol], yellow, 3, Imgproc.LINE_AA, 0, 0.3)
            }
        }

        val bmp = matToBitmap(mat)
        mat.release()

        val canvas = Canvas(bmp)
        drawPieceLabels(canvas, grid, board)

        return bmp
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
