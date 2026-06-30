package io.github.jiangood.xq.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.roundToInt
import io.github.jiangood.xq.analysis.AnalysisEngine
import io.github.jiangood.xq.opencv.CalibrationData
import io.github.jiangood.xq.opencv.CalibrationTemplate
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

private val PIECE_CHINESE = io.github.jiangood.xq.util.FenUtil.PIECE_CHINESE

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
    var gridPx by remember { mutableIntStateOf(0) }
    var yOffset by remember { mutableIntStateOf(0) }
    var imgFitScale by remember { mutableFloatStateOf(1f) }
    var testResult by remember { mutableStateOf<TestResult?>(null) }
    var testing by remember { mutableStateOf(false) }

    val pickMedia = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                startCalibration(context, uri) { newState ->
                    state = newState
                    gridPx = 0
                    yOffset = 0
                    testResult = null
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (CalibrationManager.hasOriginalImage(context) && state is CalibrationUiState.Idle) {
            loadExistingCalibration(context) { newState ->
                state = newState
                gridPx = 0
                yOffset = 0
                testResult = null
            }
        }
    }

    fun cleanup() {
        val s = state as? CalibrationUiState.Ready
        s?.mat?.release()
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
        when (val s = state) {
            is CalibrationUiState.Idle -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Button(onClick = {
                        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) {
                        Text(
                            if (CalibrationManager.hasOriginalImage(context)) "替换图片" else "选择校准图片",
                            fontSize = 18.sp
                        )
                    }
                }
            }

            is CalibrationUiState.Processing -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("正在检测棋盘网格...")
                    }
                }
            }

            is CalibrationUiState.Ready -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        OutlinedButton(onClick = {
                            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) {
                            Text("替换图片")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(s.bitmap.width.toFloat() / s.bitmap.height.toFloat())
                            .background(Color(0xFF333333))
                            .onSizeChanged { size ->
                                val iScale = minOf(
                                    size.width.toFloat() / s.bitmap.width,
                                    size.height.toFloat() / s.bitmap.height
                                ).coerceAtMost(1f)
                                imgFitScale = iScale
                                if (gridPx == 0) {
                                    gridPx = (iScale * s.cellSize).roundToInt()
                                }
                            }
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val bmp = s.bitmap
                            val fitScale = minOf(
                                size.width / bmp.width,
                                size.height / bmp.height
                            ).coerceAtMost(1f)
                            val imgW = bmp.width * fitScale
                            val imgH = bmp.height * fitScale
                            val imgX = (size.width - imgW) / 2f
                            val imgY = (size.height - imgH) / 2f

                            drawImage(
                                image = bmp.asImageBitmap(),
                                dstOffset = IntOffset(imgX.toInt(), imgY.toInt()),
                                dstSize = IntSize(imgW.toInt(), imgH.toInt())
                            )

                            if (gridPx > 0) {
                                val grid = s.grid
                                val curScale = gridPx / s.cellSize
                                val curRatio = curScale / fitScale
                                val gridCx = (grid[0][0].x + grid[0][8].x + grid[9][0].x + grid[9][8].x) / 4.0
                                val gridCy = (grid[0][0].y + grid[0][8].y + grid[9][0].y + grid[9][8].y) / 4.0
                                val gX = imgX + (gridCx * fitScale * (1 - curRatio)).toFloat()
                                val gY = imgY + (gridCy * fitScale * (1 - curRatio)).toFloat() + yOffset
                                for (r in 0 until 10) {
                                    val x1 = gX + (grid[r][0].x * curScale).toFloat()
                                    val y1 = gY + (grid[r][0].y * curScale).toFloat()
                                    val x2 = gX + (grid[r][8].x * curScale).toFloat()
                                    val y2 = gY + (grid[r][8].y * curScale).toFloat()
                                    drawLine(Color.Green, Offset(x1, y1), Offset(x2, y2), strokeWidth = 2f)
                                }
                                for (c in 0 until 9) {
                                    val x1 = gX + (grid[0][c].x * curScale).toFloat()
                                    val y1 = gY + (grid[0][c].y * curScale).toFloat()
                                    val x2 = gX + (grid[9][c].x * curScale).toFloat()
                                    val y2 = gY + (grid[9][c].y * curScale).toFloat()
                                    drawLine(Color.Green, Offset(x1, y1), Offset(x2, y2), strokeWidth = 2f)
                                }
                                for (r in 0 until 10) {
                                    for (c in 0 until 9) {
                                        val px = gX + (grid[r][c].x * curScale).toFloat()
                                        val py = gY + (grid[r][c].y * curScale).toFloat()
                                        drawCircle(Color.Red, radius = 4f, center = Offset(px, py))
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "缩放",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(64.dp),
                            textAlign = TextAlign.End
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = { gridPx = (gridPx - 1).coerceIn(5, 500) }) {
                            Text("−", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(modifier = Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                            Text(gridPx.toString(), style = MaterialTheme.typography.titleMedium)
                        }
                        IconButton(onClick = { gridPx = (gridPx + 1).coerceIn(5, 500) }) {
                            Icon(Icons.Filled.Add, contentDescription = "放大")
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "上下移动",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(64.dp),
                            textAlign = TextAlign.End
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = { yOffset = (yOffset - 1).coerceIn(-9999, 9999) }) {
                            Text("−", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(modifier = Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                            Text(yOffset.toString(), style = MaterialTheme.typography.titleMedium)
                        }
                        IconButton(onClick = { yOffset = (yOffset + 1).coerceIn(-9999, 9999) }) {
                            Icon(Icons.Filled.Add, contentDescription = "下移")
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    val templates = remember(s.mat, s.grid, s.cellSize, gridPx) {
                        val zoomRatio = if (imgFitScale > 0) gridPx / (imgFitScale * s.cellSize) else 1.0
                        cropTemplates(s.mat, s.grid, s.cellSize, zoomRatio)
                    }
                    @Composable fun PieceItem(type: String, bmp: Bitmap) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(36.dp)
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = type,
                                modifier = Modifier
                                    .width(34.dp)
                                    .height(40.dp)
                                    .background(Color(0xFFE0E0E0))
                            )
                            Text(
                                text = PIECE_CHINESE[type] ?: type,
                                fontSize = 9.sp,
                                color = if (type.startsWith("r")) Color(0xFFD32F2F) else Color(0xFF1976D2)
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        templates.take(7).forEach { (type, bmp) -> PieceItem(type, bmp) }
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        templates.drop(7).forEach { (type, bmp) -> PieceItem(type, bmp) }
                    }

                    Spacer(Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
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
                    }

                    if (testResult != null) {
                        TestResultSection(testResult!!)
                    }
                }
            }

            is CalibrationUiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(s.message, color = Color.Red, fontSize = 16.sp)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = {
                            state = CalibrationUiState.Idle
                        }) {
                            Text("重新选择")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TestResultSection(result: TestResult) {
    Column(
        modifier = Modifier.fillMaxWidth(),
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

private suspend fun runTest(
    context: android.content.Context,
    state: CalibrationUiState.Ready
): TestResult = withContext(Dispatchers.IO) {
    saveCalibration(context, state)

    val board = AnalysisEngine.recognize(context, state.imagePath)
        ?: throw Exception("校准数据加载失败")

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

    val resultBitmap = AndroidImageUtils.renderBoardVisualization(state.imagePath, state.grid, board)

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
            val originalFile = CalibrationManager.getOriginalImageFile(context)
            if (!AndroidImageUtils.copyUriToFile(context.contentResolver, uri, originalFile))
                throw Exception("无法读取图片文件")

            val mat = Imgcodecs.imread(originalFile.absolutePath, Imgcodecs.IMREAD_COLOR)
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
            val readyState = CalibrationUiState.Ready(bitmap, grid, cellSizeEst, imageWidth, imageHeight, cropped, originalFile.absolutePath)
            saveCalibration(context, readyState)
            onResult(readyState)
        }
    } catch (e: Exception) {
        onResult(CalibrationUiState.Error("校准失败: ${e.message ?: "未知错误"}"))
    }
}

private suspend fun loadExistingCalibration(
    context: android.content.Context,
    onResult: (CalibrationUiState) -> Unit
) {
    onResult(CalibrationUiState.Processing)
    try {
        withContext(Dispatchers.IO) {
            val calibData = CalibrationManager.load(context)
                ?: throw Exception("校准数据加载失败")
            val originalFile = CalibrationManager.getOriginalImageFile(context)
            if (!originalFile.exists()) throw Exception("原始图片不存在")

            val mat = Imgcodecs.imread(originalFile.absolutePath, Imgcodecs.IMREAD_COLOR)
            if (mat.empty()) throw Exception("无法加载图片")

            val cropped = io.github.jiangood.xq.opencv.BoardUtils.cropBoardCenter(mat)
            mat.release()

            val bitmap = AndroidImageUtils.matToBitmap(cropped)

            onResult(CalibrationUiState.Ready(
                bitmap = bitmap,
                grid = calibData.grid,
                cellSize = calibData.cellSize,
                imageWidth = calibData.imageWidth,
                imageHeight = calibData.imageHeight,
                mat = cropped,
                imagePath = originalFile.absolutePath
            ))
        }
    } catch (e: Exception) {
        onResult(CalibrationUiState.Error("加载失败: ${e.message ?: "未知错误"}"))
    }
}

private fun cropTemplates(mat: Mat, grid: Array<Array<Point>>, cellSize: Double, zoomRatio: Double = 1.0): List<Pair<String, Bitmap>> {
    val pieceSize = cellSize * 0.65 * zoomRatio
    val result = mutableListOf<Pair<String, Bitmap>>()
    val savedTypes = mutableSetOf<String>()
    for (r in 0 until 10) {
        for (c in 0 until 9) {
            val pieceType = STANDARD_OPENING[r][c] ?: continue
            if (pieceType in savedTypes) continue
            savedTypes.add(pieceType)
            val pieceMat = CalibrationManager.cropPiece(mat, grid, r, c, pieceSize) ?: continue
            val bmp = AndroidImageUtils.matToBitmap(pieceMat)
            pieceMat.release()
            result.add(pieceType to bmp)
        }
    }
    return result
}

private fun saveCalibration(context: android.content.Context, state: CalibrationUiState.Ready) {
    val pieceSize = state.cellSize * 0.65
    val data = CalibrationData()
    data.imageWidth = state.imageWidth
    data.imageHeight = state.imageHeight
    data.cellSize = state.cellSize
    data.pieceSize = pieceSize
    data.grid = state.grid
    data.templates = mutableListOf()

    val dir = CalibrationManager.getDir(context)
    val savedTypes = mutableSetOf<String>()

    for (r in 0 until 10) {
        for (c in 0 until 9) {
            val pieceType = STANDARD_OPENING[r][c] ?: continue
            if (pieceType in savedTypes) continue
            savedTypes.add(pieceType)
            val pieceMat = CalibrationManager.cropPiece(state.mat, state.grid, r, c, pieceSize) ?: continue
            val filename = "${pieceType}.png"
            Imgcodecs.imwrite(File(dir, filename).absolutePath, pieceMat)
            pieceMat.release()
            data.templates.add(CalibrationTemplate(filename, pieceType))
        }
    }

    CalibrationManager.save(context, data)
}

internal fun isSaveEnabled(testPassed: Boolean?): Boolean {
    return testPassed == true
}
