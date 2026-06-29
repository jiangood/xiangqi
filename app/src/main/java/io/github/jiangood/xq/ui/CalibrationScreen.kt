package io.github.jiangood.xq.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jiangood.xq.opencv.CalibrationData
import io.github.jiangood.xq.opencv.CalibrationTemplate
import io.github.jiangood.xq.opencv.TemplatePieceRecognizer
import io.github.jiangood.xq.platform.AndroidImageUtils
import io.github.jiangood.xq.settings.CalibrationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

private sealed class CalibrationUiState {
    object Idle : CalibrationUiState()
    object Processing : CalibrationUiState()
    data class Ready(
        val bitmap: Bitmap,
        val grid: Array<Array<Point>>,
        val cellSize: Double,
        val imageWidth: Int,
        val imageHeight: Int,
        val mat: Mat,
        val imagePath: String
    ) : CalibrationUiState()
    data class Error(val message: String) : CalibrationUiState()
}

private val PIECE_LABELS = mapOf(
    "rk" to "帅", "ra" to "仕", "rb" to "相", "rr" to "车",
    "rn" to "马", "rc" to "炮", "rp" to "兵",
    "bk" to "将", "ba" to "士", "bb" to "象", "br" to "車",
    "bn" to "馬", "bc" to "砲", "bp" to "卒"
)

private val STANDARD_OPENING: Array<Array<String?>> = arrayOf(
    arrayOf("bk", "bb", "bc", "ba", "bk", "ba", "bc", "bb", "bk"),
    arrayOf(null, null, null, null, null, null, null, null, null),
    arrayOf(null, "bc", null, null, null, null, null, "bc", null),
    arrayOf("bp", null, "bp", null, "bp", null, "bp", null, "bp"),
    arrayOf(null, null, null, null, null, null, null, null, null),
    arrayOf(null, null, null, null, null, null, null, null, null),
    arrayOf("rp", null, "rp", null, "rp", null, "rp", null, "rp"),
    arrayOf(null, "rc", null, null, null, null, null, "rc", null),
    arrayOf(null, null, null, null, null, null, null, null, null),
    arrayOf("rk", "rr", "rc", "ra", "rk", "ra", "rc", "rr", "rk")
)

private fun detectOrientation(mat: Mat, grid: Array<Array<Point>>): Boolean {
    val p0 = grid[0][4]
    val p9 = grid[9][4]
    fun avgRed(p: Point): Double {
        val x = p.x.toInt(); val y = p.y.toInt()
        val r = Rect(maxOf(0, x - 5), maxOf(0, y - 5), 10, 10)
        if (r.x + r.width > mat.cols() || r.y + r.height > mat.rows()) return 0.0
        val region = Mat(mat, r)
        val sum = Core.sumElems(region)
        region.release()
        return sum.`val`[2]
    }
    return avgRed(p0) < avgRed(p9)
}

private fun mirrorOpening(standard: Array<Array<String?>>): Array<Array<String?>> {
    val flipped = Array(10) { arrayOfNulls<String?>(9) }
    for (r in 0 until 10) {
        for (c in 0 until 9) {
            flipped[9 - r][8 - c] = standard[r][c]?.let {
                if (it.startsWith("r")) "b" + it.substring(1)
                else "r" + it.substring(1)
            }
        }
    }
    return flipped
}

private data class TestResult(
    val passed: Boolean,
    val total: Int,
    val correct: Int,
    val mismatches: List<String>,
    val recognized: Array<Array<String?>>
) {
    val score: String get() = "$correct/$total"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<CalibrationUiState>(CalibrationUiState.Idle) }
    var flipped by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var testResult by remember { mutableStateOf<TestResult?>(null) }
    var testing by remember { mutableStateOf(false) }

    val pickMedia = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                startCalibration(context, uri) { newState ->
                    state = newState
                    scale = 1f
                    offset = Offset.Zero
                }
            }
        }
    }

    fun cleanup() {
        val s = state as? CalibrationUiState.Ready
        s?.mat?.release()
        s?.imagePath?.let { File(it).delete() }
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("棋盘棋子校准") },
                navigationIcon = {
                    IconButton(onClick = { cleanup() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (val s = state) {
                is CalibrationUiState.Idle -> {
                    Spacer(Modifier.weight(1f))
                    Button(onClick = {
                        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) {
                        Text("选择校准图片", fontSize = 18.sp)
                    }
                    Spacer(Modifier.weight(1f))
                }

                is CalibrationUiState.Processing -> {
                    Spacer(Modifier.weight(1f))
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("正在检测棋盘网格...")
                    Spacer(Modifier.weight(1f))
                }

                is CalibrationUiState.Ready -> {
                    val orientationCorrect = remember(s, flipped) {
                        val normal = detectOrientation(s.mat, s.grid)
                        if (!flipped) normal else !normal
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color(0xFF333333))
                    ) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        scale = (scale * zoom).coerceIn(1f, 5f)
                                        offset += pan
                                    }
                                }
                        ) {
                            val bmp = s.bitmap
                            val fitScale = minOf(
                                size.width / bmp.width,
                                size.height / bmp.height
                            ).coerceAtMost(1f)
                            val totalScale = fitScale * scale
                            val imgW = bmp.width * totalScale
                            val imgH = bmp.height * totalScale
                            val imgX = (size.width - imgW) / 2f + offset.x
                            val imgY = (size.height - imgH) / 2f + offset.y

                            drawImage(
                                image = bmp.asImageBitmap(),
                                dstOffset = IntOffset(imgX.toInt(), imgY.toInt()),
                                dstSize = IntSize(imgW.toInt(), imgH.toInt())
                            )

                            val grid = s.grid
                            for (r in 0 until 10) {
                                val x1 = imgX + (grid[r][0].x * totalScale).toFloat()
                                val y1 = imgY + (grid[r][0].y * totalScale).toFloat()
                                val x2 = imgX + (grid[r][8].x * totalScale).toFloat()
                                val y2 = imgY + (grid[r][8].y * totalScale).toFloat()
                                drawLine(Color.Green, Offset(x1, y1), Offset(x2, y2), strokeWidth = 2f)
                            }
                            for (c in 0 until 9) {
                                val x1 = imgX + (grid[0][c].x * totalScale).toFloat()
                                val y1 = imgY + (grid[0][c].y * totalScale).toFloat()
                                val x2 = imgX + (grid[9][c].x * totalScale).toFloat()
                                val y2 = imgY + (grid[9][c].y * totalScale).toFloat()
                                drawLine(Color.Green, Offset(x1, y1), Offset(x2, y2), strokeWidth = 2f)
                            }
                            for (r in 0 until 10) {
                                for (c in 0 until 9) {
                                    val px = imgX + (grid[r][c].x * totalScale).toFloat()
                                    val py = imgY + (grid[r][c].y * totalScale).toFloat()
                                    drawCircle(Color.Red, radius = 4f, center = Offset(px, py))
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Text(
                        "方向: ${if (orientationCorrect) "黑上红下 ✓" else "红上黑下 (需互换)"}",
                        fontSize = 14.sp,
                        color = if (orientationCorrect) Color(0xFF4CAF50) else Color(0xFFFF9800)
                    )

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                    ) {
                        OutlinedButton(onClick = { flipped = !flipped }) {
                            Text("⇄ 红黑互换")
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    testing = true
                                    testResult = runTest(context, s, orientationCorrect)
                                    testing = false
                                }
                            },
                            enabled = !testing
                        ) {
                            if (testing) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Text("测试")
                            }
                        }
                        Button(onClick = {
                            saveCalibration(context, s, orientationCorrect)
                            s.mat.release()
                            s.imagePath.let { File(it).delete() }
                            onBack()
                        }) {
                            Text("确认校准")
                        }
                    }
                }

                is CalibrationUiState.Error -> {
                    Spacer(Modifier.weight(1f))
                    Text(s.message, color = Color.Red, fontSize = 16.sp)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        state = CalibrationUiState.Idle
                    }) {
                        Text("重新选择")
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }

    if (testResult != null) {
        TestResultDialog(
            result = testResult!!,
            onDismiss = { testResult = null }
        )
    }
}

@Composable
private fun TestResultDialog(
    result: TestResult,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (result.passed) "测试通过 ✓" else "测试失败 ✗",
                color = if (result.passed) Color(0xFF4CAF50) else Color(0xFFE53935),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("正确识别: ${result.score}")
                Text("准确率: ${String.format("%.1f", result.correct * 100.0 / result.total)}%")

                if (result.mismatches.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("错误详情:", fontWeight = FontWeight.Bold)
                    result.mismatches.take(20).forEach { msg ->
                        Text(msg, fontSize = 13.sp, color = Color(0xFFE53935))
                    }
                    if (result.mismatches.size > 20) {
                        Text("... 还有 ${result.mismatches.size - 20} 个错误", fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("确定")
            }
        }
    )
}

private suspend fun runTest(
    context: android.content.Context,
    state: CalibrationUiState.Ready,
    orientationStandard: Boolean
): TestResult = withContext(Dispatchers.IO) {
    saveCalibration(context, state, orientationStandard)

    val calibData = CalibrationManager.load(context) ?: throw Exception("校准数据加载失败")
    val templateDir = CalibrationManager.getTemplateFileDir(context)
    val recognizer = TemplatePieceRecognizer(calibData, templateDir)

    val board = recognizer.parseBoard(state.imagePath)

    val opening = if (orientationStandard) STANDARD_OPENING else mirrorOpening(STANDARD_OPENING)

    var correct = 0
    var total = 0
    val mismatches = mutableListOf<String>()

    for (r in 0 until 10) {
        for (c in 0 until 9) {
            val expected = opening[r][c]
            val actual = board[r][c]
            if (expected == null && actual == null) continue
            total++
            if (expected == actual) {
                correct++
            } else {
                val pos = "($r,$c)"
                val expStr = expected?.let { PIECE_LABELS[it] ?: it } ?: "空"
                val actStr = actual?.let { PIECE_LABELS[it] ?: it } ?: "空"
                mismatches.add("$pos: 期望 $expStr, 识别为 $actStr")
            }
        }
    }

    TestResult(
        passed = correct == total,
        total = total,
        correct = correct,
        mismatches = mismatches,
        recognized = board
    )
}

private suspend fun startCalibration(
    context: android.content.Context,
    uri: Uri,
    onResult: (CalibrationUiState) -> Unit
) {
    onResult(CalibrationUiState.Processing)
    try {
        withContext(Dispatchers.IO) {
            val tempFile = File(context.cacheDir, "calib_${System.nanoTime()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }

            val mat = Imgcodecs.imread(tempFile.absolutePath, Imgcodecs.IMREAD_COLOR)
            if (mat.empty()) throw Exception("无法加载图片")

            val cropped = io.github.jiangood.xq.opencv.BoardUtils.cropBoardCenter(mat)
            val imageWidth = mat.width()
            val imageHeight = mat.height()
            mat.release()

            val gray = Mat()
            Imgproc.cvtColor(cropped, gray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.threshold(gray, gray, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
            val binary = gray.clone()
            gray.release()

            val boardRect = Rect(0, 0, cropped.width(), cropped.height())
            val cellSizeEst = binary.width() / 9.0
            val detectedLines = io.github.jiangood.xq.opencv.BoardUtils.detectGridLines(binary, cellSizeEst)
            val grid = io.github.jiangood.xq.opencv.BoardUtils.calibrateGrid(
                java.util.LinkedHashMap(), boardRect, binary, detectedLines[0], detectedLines[1]
            )
            binary.release()

            val bitmap = AndroidImageUtils.matToBitmap(cropped)
            onResult(CalibrationUiState.Ready(bitmap, grid, cellSizeEst, imageWidth, imageHeight, cropped, tempFile.absolutePath))
        }
    } catch (e: Exception) {
        onResult(CalibrationUiState.Error("校准失败: ${e.message ?: "未知错误"}"))
    }
}

private fun saveCalibration(context: android.content.Context, state: CalibrationUiState.Ready, orientationStandard: Boolean) {
    val opening = if (orientationStandard) STANDARD_OPENING else mirrorOpening(STANDARD_OPENING)
    val pieceSize = state.cellSize * 0.85
    val data = CalibrationData()
    data.imageWidth = state.imageWidth
    data.imageHeight = state.imageHeight
    data.cellSize = state.cellSize
    data.pieceSize = pieceSize
    data.grid = state.grid
    data.templates = mutableListOf()

    val dir = CalibrationManager.getDir(context)
    var counter = 0

    for (r in 0 until 10) {
        for (c in 0 until 9) {
            val pieceType = opening[r][c] ?: continue
            val pieceMat = CalibrationManager.cropPiece(state.mat, state.grid, r, c, pieceSize) ?: continue
            val filename = "${pieceType}_${counter++}.png"
            Imgcodecs.imwrite(File(dir, filename).absolutePath, pieceMat)
            pieceMat.release()
            data.templates.add(CalibrationTemplate(filename, pieceType))
        }
    }

    CalibrationManager.save(context, data)
}
