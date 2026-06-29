package io.github.jiangood.xq

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jiangood.xq.opencv.*
import io.github.jiangood.xq.settings.CalibrationManager
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

@RunWith(AndroidJUnit4::class)
class CalibrationIntegrationTest {

    companion object {
        private val STANDARD_OPENING: Array<Array<String?>> = arrayOf(
            arrayOf("br", "bn", "bb", "ba", "bk", "ba", "bb", "bn", "br"),
            arrayOf(null, null, null, null, null, null, null, null, null),
            arrayOf(null, "bc", null, null, null, null, null, "bc", null),
            arrayOf("bp", null, "bp", null, "bp", null, "bp", null, "bp"),
            arrayOf(null, null, null, null, null, null, null, null, null),
            arrayOf(null, null, null, null, null, null, null, null, null),
            arrayOf("rp", null, "rp", null, "rp", null, "rp", null, "rp"),
            arrayOf(null, "rc", null, null, null, null, null, "rc", null),
            arrayOf(null, null, null, null, null, null, null, null, null),
            arrayOf("rr", "rn", "rb", "ra", "rk", "ra", "rb", "rn", "rr")
        )

        @BeforeClass @JvmStatic
        fun init() {
            System.loadLibrary("opencv_java4")
        }
    }

    @Test
    fun calibrateAndRecognize_style1() {
        runCalibrationTest("style1.jpg")
    }

    @Test
    fun calibrateAndRecognize_style2() {
        runCalibrationTest("style2.jpg")
    }

    private fun runCalibrationTest(imageName: String) {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val testContext = InstrumentationRegistry.getInstrumentation().context

        val imageFile = File(targetContext.cacheDir, imageName)
        testContext.assets.open("test-images/$imageName").use { input ->
            imageFile.outputStream().use { output -> input.copyTo(output) }
        }

        try {
            val mat = Imgcodecs.imread(imageFile.absolutePath)
            assertNotNull("Failed to load image", mat)

            val imageWidth = mat.width()
            val imageHeight = mat.height()
            val cropped = BoardUtils.cropBoardCenter(mat)
            mat.release()

            // 校准界面的网格检测逻辑
            val gray = Mat()
            Imgproc.cvtColor(cropped, gray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.threshold(gray, gray, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
            val binary = gray.clone()
            gray.release()

            val boardRect = Rect(0, 0, cropped.width(), cropped.height())
            val cellSizeEst = cropped.width() / 9.0
            val lines = BoardUtils.detectGridLines(binary, cellSizeEst)
            assertNotNull("Grid line detection failed (hChain)", lines[0])
            assertNotNull("Grid line detection failed (vChain)", lines[1])

            val grid = BoardUtils.calibrateGrid(LinkedHashMap<Point, String>(), boardRect, binary, lines[0], lines[1])
            binary.release()

            // 保存校准数据并识别
            val pieceSize = cellSizeEst * 0.65
            val calDir = File(targetContext.cacheDir, "calib_${System.nanoTime()}")
            calDir.mkdirs()

            val calibData = CalibrationData().apply {
                this.imageWidth = imageWidth
                this.imageHeight = imageHeight
                this.cellSize = cellSizeEst
                this.pieceSize = pieceSize
                this.grid = grid
                this.templates = mutableListOf()
            }

            val savedTypes = mutableSetOf<String>()
            for (r in 0 until 10) {
                for (c in 0 until 9) {
                    val pieceType = STANDARD_OPENING[r][c] ?: continue
                    if (pieceType in savedTypes) continue
                    savedTypes.add(pieceType)
                    val pieceMat = CalibrationManager.cropPiece(cropped, grid, r, c, pieceSize)
                    if (pieceMat != null) {
                        val filename = "$pieceType.png"
                        Imgcodecs.imwrite(File(calDir, filename).absolutePath, pieceMat)
                        pieceMat.release()
                        calibData.templates.add(CalibrationTemplate(filename, pieceType))
                    }
                }
            }
            cropped.release()

            val recognizer = TemplatePieceRecognizer(calibData, calDir)
            val board = recognizer.parseBoard(imageFile.absolutePath)

            var correct = 0
            var total = 0
            val mismatches = mutableListOf<String>()
            for (r in 0 until 10) {
                for (c in 0 until 9) {
                    val expected = STANDARD_OPENING[r][c]
                    val actual = board[r][c]
                    if (expected == null && actual == null) continue
                    total++
                    if (expected == actual) correct++
                    else mismatches.add("($r,$c): expected $expected, got $actual")
                }
            }

            calDir.deleteRecursively()
            imageFile.delete()

            assertTrue(
                "$imageName: $correct/$total correct.\n${mismatches.joinToString("\n")}",
                correct == total
            )
        } catch (e: Exception) {
            imageFile.delete()
            throw e
        }
    }
}
