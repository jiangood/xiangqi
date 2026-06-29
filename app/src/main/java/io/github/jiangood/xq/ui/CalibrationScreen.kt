package io.github.jiangood.xq.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.text.font.FontWeight
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
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
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

private val PIECE_CHINESE = mapOf(
    "rk" to "帅", "ra" to "仕", "rb" to "相", "rr" to "車", "rn" to "馬", "rc" to "炮", "rp" to "兵",
    "bk" to "将", "ba" to "士", "bb" to "象", "br" to "车", "bn" to "马", "bc" to "炮", "bp" to "卒"
)

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

private data class TestResult(
    val passed: Boolean,
    val total: Int,
    val correct: Int,
    val mismatches: List<String>,
    val recognized: Array<Array<String?>>,
    val resultBitmap: Bitmap?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<CalibrationUiState>(CalibrationUiState.Idle) }
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
                    testResult = null
                }
            }
        }
    }

    fun cleanup() {
        val s = state as? CalibrationUiState.Ready
        s?.mat?.release()
        s?.imagePath?.let { File(it).delete() }
        testResult?.resultBitmap?.recycle()
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
                    Box(
                        modifier = Modifier
                            .weight(if (testResult != null) 0.4f else 1f)
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
                            val imgFitScale = minOf(
                                size.width / bmp.width,
                                size.height / bmp.height
                            ).coerceAtMost(1f)
                            val imgW = bmp.width * imgFitScale
                            val imgH = bmp.height * imgFitScale
                            val imgX = (size.width - imgW) / 2f
                            val imgY = (size.height - imgH) / 2f

                            drawImage(
                                image = bmp.asImageBitmap(),
                                dstOffset = IntOffset(imgX.toInt(), imgY.toInt()),
                                dstSize = IntSize(imgW.toInt(), imgH.toInt())
                            )

                            val grid = s.grid
                            val gScale = imgFitScale * scale
                            val gX = imgX + offset.x
                            val gY = imgY + offset.y
                            for (r in 0 until 10) {
                                val x1 = gX + (grid[r][0].x * gScale).toFloat()
                                val y1 = gY + (grid[r][0].y * gScale).toFloat()
                                val x2 = gX + (grid[r][8].x * gScale).toFloat()
                                val y2 = gY + (grid[r][8].y * gScale).toFloat()
                                drawLine(Color.Green, Offset(x1, y1), Offset(x2, y2), strokeWidth = 2f)
                            }
                            for (c in 0 until 9) {
                                val x1 = gX + (grid[0][c].x * gScale).toFloat()
                                val y1 = gY + (grid[0][c].y * gScale).toFloat()
                                val x2 = gX + (grid[9][c].x * gScale).toFloat()
                                val y2 = gY + (grid[9][c].y * gScale).toFloat()
                                drawLine(Color.Green, Offset(x1, y1), Offset(x2, y2), strokeWidth = 2f)
                            }
                            for (r in 0 until 10) {
                                for (c in 0 until 9) {
                                    val px = gX + (grid[r][c].x * gScale).toFloat()
                                    val py = gY + (grid[r][c].y * gScale).toFloat()
                                    drawCircle(Color.Red, radius = 4f, center = Offset(px, py))
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    val templates = remember(s.mat, s.grid, s.cellSize) {
                        cropTemplates(s.mat, s.grid, s.cellSize)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        templates.forEach { (type, bmp) ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = type,
                                modifier = Modifier
                                    .width(34.dp)
                                    .height(48.dp)
                                    .background(Color(0xFFE0E0E0))
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                    ) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    testing = true
                                    testResult = runTest(context, s)
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
                            saveCalibration(context, s)
                            s.mat.release()
                            s.imagePath.let { File(it).delete() }
                            testResult?.resultBitmap?.recycle()
                            onBack()
                        }) {
                            Text("确认校准")
                        }
                    }

                    if (testResult != null) {
                        Column(modifier = Modifier.weight(0.5f)) {
                            TestResultSection(testResult!!)
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
}

@Composable
private fun TestResultSection(result: TestResult) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        HorizontalDivider()

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                if (result.passed) "测试通过 ✓" else "测试失败 ✗",
                color = if (result.passed) Color(0xFF4CAF50) else Color(0xFFE53935),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Text(
                "(${result.correct}/${result.total} ${String.format("%.1f", result.correct * 100.0 / result.total)}%)",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (result.resultBitmap != null) {
            Image(
                bitmap = result.resultBitmap.asImageBitmap(),
                contentDescription = "测试结果",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            )
        }

        if (result.mismatches.isNotEmpty()) {
            Text("错误详情:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            result.mismatches.take(20).forEach { msg ->
                Text(msg, fontSize = 12.sp, color = Color(0xFFE53935))
            }
            if (result.mismatches.size > 20) {
                Text("... 还有 ${result.mismatches.size - 20} 个错误", fontSize = 12.sp)
            }
        }
    }
}

private fun generateTestVisualization(
    imagePath: String,
    grid: Array<Array<Point>>,
    board: Array<Array<String?>>
): Bitmap? {
    return try {
        val img = Imgcodecs.imread(imagePath, Imgcodecs.IMREAD_COLOR) ?: return null
        val green = Scalar(0.0, 255.0, 0.0)
        val red = Scalar(0.0, 0.0, 255.0)
        val blue = Scalar(255.0, 0.0, 0.0)

        val cropY = if (img.height() > img.width() * 10.0 / 9.0)
            (img.height() - (img.width() * 10.0 / 9.0).toInt()) / 2 else 0

        for (r in 0 until 10) {
            for (c in 0 until 9) {
                val p = board[r][c] ?: continue
                val color = if (p.startsWith("r")) red else blue
                val pt = Point(grid[r][c].x, grid[r][c].y + cropY)
                Imgproc.circle(img, pt, 8, color, -1)
                Imgproc.circle(img, pt, 8, green, 2)
            }
        }

        val bmp = AndroidImageUtils.matToBitmap(img)
        img.release()
        bmp
    } catch (_: Exception) {
        null
    }
}

private suspend fun runTest(
    context: android.content.Context,
    state: CalibrationUiState.Ready
): TestResult = withContext(Dispatchers.IO) {
    saveCalibration(context, state)

    val calibData = CalibrationManager.load(context) ?: throw Exception("校准数据加载失败")
    val templateDir = CalibrationManager.getTemplateFileDir(context)
    val recognizer = TemplatePieceRecognizer(calibData, templateDir)

    val board = recognizer.parseBoard(state.imagePath)

    var correct = 0
    var total = 0
    val mismatches = mutableListOf<String>()

    for (r in 0 until 10) {
        for (c in 0 until 9) {
            val expected = STANDARD_OPENING[r][c]
            val actual = board[r][c]
            if (expected == null && actual == null) continue
            total++
            if (expected == actual) {
                correct++
            } else {
                val pos = "($r,$c)"
                val expStr = expected?.let { PIECE_CHINESE[it] ?: it } ?: "空"
                val actStr = actual?.let { PIECE_CHINESE[it] ?: it } ?: "空"
                mismatches.add("$pos: 期望 $expStr, 识别为 $actStr")
            }
        }
    }

    val resultBitmap = generateTestVisualization(state.imagePath, state.grid, board)

    TestResult(
        passed = correct == total,
        total = total,
        correct = correct,
        mismatches = mismatches,
        recognized = board,
        resultBitmap = resultBitmap
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

private fun cropTemplates(mat: Mat, grid: Array<Array<Point>>, cellSize: Double): List<Pair<String, Bitmap>> {
    val pieceSize = cellSize * 0.85
    val result = mutableListOf<Pair<String, Bitmap>>()
    for (r in 0 until 10) {
        for (c in 0 until 9) {
            val pieceType = STANDARD_OPENING[r][c] ?: continue
            val pieceMat = CalibrationManager.cropPiece(mat, grid, r, c, pieceSize) ?: continue
            val bmp = AndroidImageUtils.matToBitmap(pieceMat)
            pieceMat.release()
            result.add(pieceType to bmp)
        }
    }
    return result
}

private fun saveCalibration(context: android.content.Context, state: CalibrationUiState.Ready) {
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
            val pieceType = STANDARD_OPENING[r][c] ?: continue
            val pieceMat = CalibrationManager.cropPiece(state.mat, state.grid, r, c, pieceSize) ?: continue
            val filename = "${pieceType}_${counter++}.png"
            Imgcodecs.imwrite(File(dir, filename).absolutePath, pieceMat)
            pieceMat.release()
            data.templates.add(CalibrationTemplate(filename, pieceType))
        }
    }

    CalibrationManager.save(context, data)
}
