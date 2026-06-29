package io.github.jiangood.xq.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jiangood.xq.analysis.AnalysisEngine
import io.github.jiangood.xq.opencv.*
import io.github.jiangood.xq.platform.AndroidImageUtils
import io.github.jiangood.xq.opencv.BoardUtils
import io.github.jiangood.xq.util.AppLog
import io.github.jiangood.xq.util.FenUtil
import io.github.jiangood.xq.util.NotationConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import java.io.File

data class StepItem(
    val step: Int,
    val title: String,
    val description: String,
    val imagePath: String? = null,
    val text: String? = null
)

sealed class UiState {
    object Idle : UiState()
    object Analyzing : UiState()
    data class Result(
        val moves: List<String>,
        val standardMoves: List<String> = emptyList(),
        val steps: List<StepItem> = emptyList(),
        val validationWarnings: List<String> = emptyList(),
        val elapsedMs: Long = 0L
    ) : UiState()
    data class Error(val message: String) : UiState()
}

class AnalysisViewModel : ViewModel() {
    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState

    val logs: StateFlow<List<String>> = AppLog.logs

    fun initOpenCV(context: Context) {
        AppLog.add("OpenCV 初始化...")
        if (!OpenCVLoader.initDebug()) {
            AppLog.add("OpenCV 初始化失败")
            _uiState.value = UiState.Error("OpenCV 初始化失败")
        } else {
            AppLog.add("OpenCV 初始化成功")
        }
    }

    fun analyze(context: Context, imageUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = UiState.Analyzing
            try {
                AppLog.add("清理旧缓存...")
                AndroidImageUtils.cleanupOldAnalysisDirs(context.cacheDir)
                AppLog.add("等待引擎与识别模型就绪...")
                AnalysisEngine.awaitInitialized()
                AppLog.add("引擎与识别模型就绪")

                val board = AnalysisEngine.boardRecognizer
                val engine = AnalysisEngine.engineClient
                if (board == null || engine == null) {
                    AppLog.add("引擎或识别模型未就绪")
                    _uiState.value = UiState.Error("引擎或识别模型初始化失败")
                    return@launch
                }

                AppLog.add("复制输入图片到缓存...")
                val tempFile = File(context.cacheDir, "input_${System.nanoTime()}.jpg")
                val inputStream = context.contentResolver.openInputStream(imageUri)
                if (inputStream == null) {
                    AppLog.add("无法打开图片 URI")
                    _uiState.value = UiState.Error("无法打开图片")
                    return@launch
                }
                inputStream.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
                AppLog.add("图片已保存到: ${tempFile.name}")

                AppLog.add("开始棋盘识别...")
                val rawBoard = board.parseBoard(tempFile.absolutePath)
                AppLog.add("棋盘识别完成")

                val fixedBoard = rawBoard

                val validationWarnings = FenUtil.validatePositionDetails(fixedBoard)
                if (validationWarnings.isEmpty()) {
                    AppLog.add("局面验证通过")
                } else {
                    AppLog.add("局面验证发现 ${validationWarnings.size} 个问题:")
                    validationWarnings.forEach { AppLog.add("  ⚠ $it") }
                }

                AppLog.add("生成 FEN...")
                val fen = FenUtil.toFen(fixedBoard)
                AppLog.add("FEN: $fen")

                AppLog.add("引擎分析中...")
                val startTime = System.currentTimeMillis()
                val moves = engine.getBestMove(fen)
                val elapsedMs = System.currentTimeMillis() - startTime
                AppLog.add("引擎返回 ${moves.size} 条走法，耗时 ${elapsedMs}ms")
                val chineseMoves = moves.map { NotationConverter.convertToChineseNotation(fixedBoard, it) }

                _uiState.value = UiState.Result(moves = chineseMoves, standardMoves = moves, validationWarnings = validationWarnings, elapsedMs = elapsedMs)

                AppLog.add("生成中间步骤预览图...")
                generatePreviews(context)
                AppLog.add("分析完成")
            } catch (e: Exception) {
                AppLog.add("分析出错: ${e.message ?: "未知错误"}")
                _uiState.value = UiState.Error(e.message ?: "分析出错")
            }
        }
    }

    fun analyzeLastCapture(context: Context) {
        val file = File(context.filesDir, "last_capture.png")
        if (!file.exists()) {
            AppLog.add("没有保存的上次截屏图片")
            _uiState.value = UiState.Error("没有保存的上次截屏图片")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = UiState.Analyzing
            try {
                AppLog.add("清理旧缓存...")
                AndroidImageUtils.cleanupOldAnalysisDirs(context.cacheDir)
                AppLog.add("等待引擎与识别模型就绪...")
                AnalysisEngine.awaitInitialized()
                AppLog.add("引擎与识别模型就绪")

                val board = AnalysisEngine.boardRecognizer
                val engine = AnalysisEngine.engineClient
                if (board == null || engine == null) {
                    AppLog.add("引擎或识别模型未就绪")
                    _uiState.value = UiState.Error("引擎或识别模型初始化失败")
                    return@launch
                }

                AppLog.add("开始棋盘识别: ${file.name}")
                val rawBoard = board.parseBoard(file.absolutePath)
                AppLog.add("棋盘识别完成")

                val fixedBoard = rawBoard

                val validationWarnings = FenUtil.validatePositionDetails(fixedBoard)
                if (validationWarnings.isEmpty()) {
                    AppLog.add("局面验证通过")
                } else {
                    AppLog.add("局面验证发现 ${validationWarnings.size} 个问题:")
                    validationWarnings.forEach { AppLog.add("  ⚠ $it") }
                }

                AppLog.add("生成 FEN...")
                val fen = FenUtil.toFen(fixedBoard)
                AppLog.add("FEN: $fen")

                AppLog.add("引擎分析中...")
                val startTime = System.currentTimeMillis()
                val moves = engine.getBestMove(fen)
                val elapsedMs = System.currentTimeMillis() - startTime
                AppLog.add("引擎返回 ${moves.size} 条走法，耗时 ${elapsedMs}ms")
                val chineseMoves = moves.map { NotationConverter.convertToChineseNotation(fixedBoard, it) }

                _uiState.value = UiState.Result(moves = chineseMoves, standardMoves = moves, validationWarnings = validationWarnings, elapsedMs = elapsedMs)

                AppLog.add("生成中间步骤预览图...")
                generatePreviews(context)
                AppLog.add("分析完成")
            } catch (e: Exception) {
                AppLog.add("分析出错: ${e.message ?: "未知错误"}")
                _uiState.value = UiState.Error(e.message ?: "分析出错")
            }
        }
    }

    private suspend fun generatePreviews(context: Context) {
        val recognizer = AnalysisEngine.boardRecognizer ?: return
        if (recognizer !is YoloPieceRecognizer) return
        val ir = recognizer.lastIntermediate ?: return

        val state = _uiState.value as? UiState.Result ?: return
        ir.bestUciMove = state.standardMoves.firstOrNull()

        val cacheDir = File(context.cacheDir, "analysis_${System.nanoTime()}")
        cacheDir.mkdirs()

        try {
            val board = BoardUtils.assignPiecesToGrid(ir.correctedDetections, ir.grid, ir.boardRect)
            val valid = state.validationWarnings.isEmpty()

            val titles = listOf("原图", "中心裁剪", "灰度图", "Canny 边缘检测", "轮廓检测",
                "棋盘定位", "棋盘裁剪", "二值化", "水平线检测", "垂直线检测", "楚河汉界检测",
                "网格红线标注", "精裁棋盘", "YOLO NMS 过滤", "颜色修正",
                "棋子识别", "棋子归位", "检测统计", "二维数组", "局面验证", "FEN 识别",
                "棋盘布局", "最佳走法")
            val descs = listOf("输入的原始棋盘图片", "按 4:3 比例裁剪，去除多余背景",
                "转为灰度图，减少计算量", "Canny 算法检测边缘",
                "形态学膨胀后检测轮廓，最大轮廓=棋盘区域",
                "蓝色矩形标记检测到的棋盘位置", "按棋盘外框裁切出棋盘区域",
                "Otsu 自适应二值化，增强对比度",
                "形态学运算检测水平网格线位置", "形态学运算检测垂直网格线位置",
                "检测楚河汉界位置，确定网格校准基准",
                "红色标注校准后的完整10×9网格",
                "按网格外沿+半棋子边距精裁，去除装饰边框",
                "NMS 后最终检测结果，显示置信度",
                "根据原图颜色修正红黑方，黄色=被修正", "检测框+类别标签",
                "棋子吸附到最近网格交叉点", "YOLO 检测数量统计与参数",
                "识别结果转为10×9二维数组，中文棋子名",
                "验证棋子数量与位置是否合法", "生成 FEN 字符串",
                "程序自绘标准棋盘布局", "引擎推荐的最佳走法（黄色箭头）")

            fun path(n: Int) = File(cacheDir, "${"image_%02d".format(n)}.jpg").absolutePath

            val stepMats = listOf(
                ir.srcOriginal to "image_01.jpg",
                BoardUtils.drawCropCenter(ir) to "image_02.jpg",
                BoardUtils.toBgr(ir.srcGray) to "image_03.jpg",
                BoardUtils.drawCanny(ir) to "image_04.jpg",
                BoardUtils.drawContours(ir) to "image_05.jpg",
                BoardUtils.drawBoardRect(ir.srcOriginal, ir.boardRect) to "image_06.jpg",
                ir.boardCropped to "image_07.jpg",
                BoardUtils.toBgr(ir.boardBinary) to "image_08.jpg",
                BoardUtils.drawHLines(ir) to "image_09.jpg",
                BoardUtils.drawVLines(ir) to "image_10.jpg",
                BoardUtils.drawRiver(ir) to "image_11.jpg",
                BoardUtils.drawGridFull(ir) to "image_12.jpg",
                ir.boardRefined to "image_13.jpg",
                BoardUtils.drawRawDetections(ir) to "image_14.jpg",
                BoardUtils.drawColorCorrection(ir) to "image_15.jpg",
                BoardUtils.drawPreview(ir.srcOriginal, ir.boardRect, ir.correctedDetections, ir.grid) to "image_16.jpg",
                BoardUtils.drawPiecesSnapped(ir) to "image_17.jpg"
            )
            for ((mat, name) in stepMats) {
                AndroidImageUtils.matToJpeg(mat, File(cacheDir, name).absolutePath)
                mat.release()
            }

            val steps = mutableListOf<StepItem>()
            for (i in 0 until 17) {
                val step = i + 1
                steps.add(StepItem(step, titles[i], descs[i], imagePath = path(step)))
            }

            val detCount = ir.rawDetections.size
            val preNms = ir.yoloPreNmsCount
            steps.add(StepItem(18, titles[17], descs[17], text =
                "YOLO detections: $detCount (after NMS) / $preNms (pre-NMS, conf>25%)\nConfidence threshold: 25%  NMS threshold: 65%"))

            steps.add(StepItem(19, titles[18], descs[18], text = BoardUtils.boardToText(board)))

            val warnText = if (valid) "✓ 局面验证通过" else
                "✗ 局面验证失败:\n" + state.validationWarnings.joinToString("\n") { "  ⚠ $it" }
            steps.add(StepItem(20, titles[19], descs[19], text = warnText))

            if (valid) {
                val fen = FenUtil.toFen(board)
                steps.add(StepItem(21, titles[20], descs[20], text = "FEN: $fen"))

                val layoutMat = BoardUtils.drawBoardLayout(board)
                val layoutFile = File(cacheDir, "image_22.jpg")
                AndroidImageUtils.matToJpeg(layoutMat, layoutFile.absolutePath)
                layoutMat.release()
                steps.add(StepItem(22, titles[21], descs[21], imagePath = layoutFile.absolutePath))

                val moveMat = BoardUtils.drawMoveArrow(ir)
                val moveFile = File(cacheDir, "image_23.jpg")
                AndroidImageUtils.matToJpeg(moveMat, moveFile.absolutePath)
                moveMat.release()
                steps.add(StepItem(23, titles[22], descs[22], imagePath = moveFile.absolutePath))
            }

            val currentState = _uiState.value
            if (currentState is UiState.Result) {
                _uiState.value = currentState.copy(steps = steps)
            }
        } catch (e: Exception) {
            AppLog.add("预览图生成失败: ${e.message}")
        }
    }

    fun selectMove(@Suppress("UNUSED_PARAMETER") index: Int) {
        // single move only, no-op
    }
}
