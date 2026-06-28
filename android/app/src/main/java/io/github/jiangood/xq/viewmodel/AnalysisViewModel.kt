package io.github.jiangood.xq.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jiangood.xq.analysis.AnalysisEngine
import io.github.jiangood.xq.opencv.*
import io.github.jiangood.xq.platform.AndroidImageUtils
import io.github.jiangood.xq.util.AppLog
import io.github.jiangood.xq.util.FenUtil
import io.github.jiangood.xq.util.NotationConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import java.io.File

sealed class UiState {
    object Idle : UiState()
    object Analyzing : UiState()
    data class Result(
        val moves: List<String>,
        val standardMoves: List<String> = emptyList(),
        val stepPreviews: Map<Int, String> = emptyMap(),
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

        val state = _uiState.value
        if (state is UiState.Result) {
            ir.bestUciMove = state.standardMoves.firstOrNull()
        }

        val cacheDir = File(context.cacheDir, "analysis_${System.nanoTime()}")
        cacheDir.mkdirs()

        try {
            val stepLabels = listOf(
                "01_crop_center", "02_gray", "03_canny", "04_contours",
                "05_board_rect", "06_board_crop", "07_binary", "08_h_lines",
                "09_v_lines", "10_river", "11_grid_full", "12_raw_detections",
                "13_color_correction", "14_detections_labeled", "15_pieces_snapped",
                "16_best_move"
            )

            val stepMats = listOf(
                BoardUtils.drawCropCenter(ir),
                BoardUtils.toBgr(ir.srcGray),
                BoardUtils.drawCanny(ir),
                BoardUtils.drawContours(ir),
                BoardUtils.drawBoardRect(ir.srcOriginal, ir.boardRect),
                ir.boardCropped,
                BoardUtils.toBgr(ir.boardBinary),
                BoardUtils.drawHLines(ir),
                BoardUtils.drawVLines(ir),
                BoardUtils.drawRiver(ir),
                BoardUtils.drawGridFull(ir),
                BoardUtils.drawRawDetections(ir),
                BoardUtils.drawColorCorrection(ir),
                BoardUtils.drawPreview(ir.srcOriginal, ir.boardRect, ir.correctedDetections, ir.grid),
                BoardUtils.drawPiecesSnapped(ir),
                BoardUtils.drawMoveArrow(ir)
            )

            val paths = mutableMapOf<Int, String>()

            for (i in stepMats.indices) {
                val stepNum = i + 1
                val fileName = "${stepLabels[i]}.jpg"
                val file = File(cacheDir, fileName)
                AndroidImageUtils.matToJpeg(stepMats[i], file.absolutePath)
                stepMats[i].release()
                paths[stepNum] = file.absolutePath
            }

            val currentState = _uiState.value
            if (currentState is UiState.Result) {
                _uiState.value = currentState.copy(stepPreviews = paths)
            }
        } catch (e: Exception) {
            AppLog.add("预览图生成失败: ${e.message}")
        }
    }

    fun selectMove(@Suppress("UNUSED_PARAMETER") index: Int) {
        // single move only, no-op
    }
}
