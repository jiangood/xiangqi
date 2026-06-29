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
import org.opencv.core.Rect
import java.io.File

data class StepItem(
    val step: Int,
    val title: String,
    val description: String,
    val hasImage: Boolean = false,
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
        val elapsedMs: Long = 0L,
        val imageDir: String? = null
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

    private suspend fun generatePreviews(context: Context) {
        val recognizer = AnalysisEngine.boardRecognizer ?: return
        if (recognizer !is YoloPieceRecognizer) return
        val ir = recognizer.lastIntermediate ?: return

        val state = _uiState.value as? UiState.Result ?: return
        ir.bestUciMove = state.standardMoves.firstOrNull()

        val cacheDir = File(context.cacheDir, "analysis_${System.nanoTime()}")
        cacheDir.mkdirs()

        try {
            val board = BoardUtils.assignPiecesToGrid(ir.correctedDetections, ir.grid)
            val valid = state.validationWarnings.isEmpty()

            // Save image mats — filename auto-generated from step number
            val imageSteps = listOf(
                1 to ir.srcOriginal,
                2 to BoardUtils.toBgr(ir.boardBinary),
                3 to BoardUtils.drawHLines(ir),
                4 to BoardUtils.drawVLines(ir),
                5 to BoardUtils.drawGridFull(ir),
                6 to BoardUtils.drawRawDetections(ir),
                7 to BoardUtils.drawColorCorrection(ir),
                8 to BoardUtils.drawPreview(ir.boardCropped, Rect(0, 0, ir.boardCropped.width(), ir.boardCropped.height()), ir.correctedDetections, ir.grid),
                9 to BoardUtils.drawRefinedPiecesSnapped(ir)
            )
            for ((step, mat) in imageSteps) {
                AndroidImageUtils.matToJpeg(mat, File(cacheDir, "image_%02d.jpg".format(step)).absolutePath)
                mat.release()
            }

            val steps = mutableListOf<StepItem>()
            steps.add(StepItem(1, "中心裁切", "按宽:高=9:10 中心裁切，去除多余背景", hasImage = true))
            steps.add(StepItem(2, "二值化", "Otsu 自适应二值化，增强对比度", hasImage = true))
            steps.add(StepItem(3, "水平线检测", "形态学运算检测水平网格线位置", hasImage = true))
            steps.add(StepItem(4, "垂直线检测", "形态学运算检测垂直网格线位置", hasImage = true))
            steps.add(StepItem(5, "网格校准", "由检测到的线链中心外推完整 10×9 网格", hasImage = true))
            steps.add(StepItem(6, "YOLO NMS 过滤", "NMS 后最终检测结果，显示置信度", hasImage = true))
            steps.add(StepItem(7, "颜色修正", "根据棋盘颜色修正红黑方，黄色=被修正", hasImage = true))
            steps.add(StepItem(8, "棋子识别", "检测框+类别标签", hasImage = true))
            steps.add(StepItem(9, "棋子归位", "棋子吸附到最近网格交叉点", hasImage = true))

            val detCount = ir.rawDetections.size
            val preNms = ir.yoloPreNmsCount
            steps.add(StepItem(10, "检测统计", "YOLO 检测数量统计与参数", text =
                "YOLO detections: $detCount (after NMS) / $preNms (pre-NMS, conf>25%)\nConfidence threshold: 25%  NMS threshold: 65%"))

            steps.add(StepItem(11, "二维数组", "识别结果转为10×9二维数组，中文棋子名", text = BoardUtils.boardToText(board)))

            val warnText = if (valid) "✓ 局面验证通过" else
                "✗ 局面验证失败:\n" + state.validationWarnings.joinToString("\n") { "  ⚠ $it" }
            steps.add(StepItem(12, "局面验证", "验证棋子数量与位置是否合法", text = warnText))

            if (valid) {
                val fen = FenUtil.toFen(board)
                steps.add(StepItem(13, "FEN 识别", "生成 FEN 字符串", text = "FEN: $fen"))

                val layoutMat = BoardUtils.drawBoardLayout(board)
                AndroidImageUtils.matToJpeg(layoutMat, File(cacheDir, "image_14.jpg").absolutePath)
                layoutMat.release()
                steps.add(StepItem(14, "棋盘布局", "程序自绘标准棋盘布局", hasImage = true))

                val moveMat = BoardUtils.drawRefinedMoveArrow(ir)
                AndroidImageUtils.matToJpeg(moveMat, File(cacheDir, "image_15.jpg").absolutePath)
                moveMat.release()
                steps.add(StepItem(15, "最佳走法", "引擎推荐的最佳走法（黄色箭头）", hasImage = true))
            }

            val currentState = _uiState.value
            if (currentState is UiState.Result) {
                _uiState.value = currentState.copy(steps = steps, imageDir = cacheDir.absolutePath)
            }
        } catch (e: Exception) {
            AppLog.add("预览图生成失败: ${e.message}")
        }
    }

    fun selectMove(@Suppress("UNUSED_PARAMETER") index: Int) {
        // single move only, no-op
    }
}
