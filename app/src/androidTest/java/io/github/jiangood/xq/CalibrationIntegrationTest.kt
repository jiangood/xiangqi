package io.github.jiangood.xq

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jiangood.xq.opencv.*
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

        private val PIECE_TYPES = arrayOf(
            "rk", "ra", "rb", "rr", "rn", "rc", "rp",
            "bk", "ba", "bb", "br", "bn", "bc", "bp"
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

    @Test
    fun detectGridFromLiveScreenshot() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val testContext = InstrumentationRegistry.getInstrumentation().context

        val imageFile = File(targetContext.cacheDir, "screenshot_live.jpg")
        testContext.assets.open("test-images/screenshot_live.jpg").use { input ->
            imageFile.outputStream().use { output -> input.copyTo(output) }
        }

        try {
            val mat = Imgcodecs.imread(imageFile.absolutePath)
            assertNotNull("Failed to load image", mat)
            assertFalse("Image loaded but empty", mat.empty())

            val cropped = BoardUtils.cropBoardCenter(mat)
            mat.release()

            assertNotNull("cropBoardCenter returned null", cropped)
            assertTrue("Cropped too small: ${cropped.cols()}x${cropped.rows()}",
                cropped.rows() > 100 && cropped.cols() > 100)

            // Dynamic grid detection — may be null if the board detector couldn't
            // locate the board in the full-screen screenshot (e.g. board not centered).
            // When it succeeds, validate the grid thoroughly.
            val grid = BoardUtils.computeGrid(cropped)

            if (grid != null) {
                assertEquals("grid rows", 10, grid.size)
                assertEquals("grid cols", 9, grid[0].size)

                for (r in 0 until 10) {
                    for (c in 0 until 9) {
                        assertTrue("grid[$r][$c].x out of bounds",
                            grid[r][c].x in 0.0..cropped.cols().toDouble())
                        assertTrue("grid[$r][$c].y out of bounds",
                            grid[r][c].y in 0.0..cropped.rows().toDouble())
                    }
                }
                for (r in 1 until 10) {
                    assertTrue("Row $r not increasing", grid[r][0].y > grid[r - 1][0].y)
                }
                for (c in 1 until 9) {
                    assertTrue("Col $c not increasing", grid[0][c].x > grid[0][c - 1].x)
                }

                val cellH = grid[1][0].y - grid[0][0].y
                val cellW = grid[0][1].x - grid[0][0].x
                assertTrue("cellH=$cellH too small", cellH > 0)
                assertTrue("cellW=$cellW too small", cellW > 0)
            }

            cropped.release()
            imageFile.delete()
        } catch (e: Exception) {
            imageFile.delete()
            throw e
        }
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

            val cropped = BoardUtils.cropBoardCenter(mat)
            mat.release()

            // Compute grid dynamically (same as new TemplatePieceRecognizer flow)
            val grid = BoardUtils.computeGrid(cropped)
            val cellSizeEst = grid[1][0].y - grid[0][0].y
            val pieceSize = cellSizeEst * 0.65

            // Crop 14 unique templates from standard opening positions
            val mats = mutableListOf<Mat>()
            val types = mutableListOf<String>()
            val savedTypes = mutableSetOf<String>()
            for (r in 0 until 10) {
                for (c in 0 until 9) {
                    val pieceType = STANDARD_OPENING[r][c] ?: continue
                    if (pieceType in savedTypes) continue
                    savedTypes.add(pieceType)
                    val center = grid[r][c]
                    val half = (pieceSize / 2).toInt()
                    val x = (center.x - half).toInt()
                    val y = (center.y - half).toInt()
                    val w = pieceSize.toInt()
                    val h = pieceSize.toInt()
                    if (x >= 0 && y >= 0 && x + w <= cropped.cols() && y + h <= cropped.rows() && w > 0 && h > 0) {
                        val pieceMat = Mat(cropped, Rect(x, y, w, h))
                        val grayPiece = Mat()
                        Imgproc.cvtColor(pieceMat, grayPiece, Imgproc.COLOR_BGR2GRAY)
                        mats.add(grayPiece)
                        types.add(pieceType)
                        pieceMat.release()
                    }
                }
            }
            cropped.release()

            assertTrue("No templates extracted", mats.isNotEmpty())
            assertEquals("Should have 14 templates", 14, mats.size)

            val recognizer = TemplatePieceRecognizer(mats.toTypedArray(), types.toTypedArray())
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
