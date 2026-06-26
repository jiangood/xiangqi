package io.github.jiangood.xq.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jiangood.xq.engine.AndroidEngineClient
import io.github.jiangood.xq.opencv.*
import io.github.jiangood.xq.platform.AndroidImageUtils
import io.github.jiangood.xq.util.FenUtil
import io.github.jiangood.xq.util.NotationConverter
import kotlinx.coroutines.CompletableDeferred
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
        val currentMoveIndex: Int = 0,
        val stepPreviews: Map<Int, Bitmap> = emptyMap(),
        val validationWarnings: List<String> = emptyList()
    ) : UiState()
    data class Error(val message: String) : UiState()
}

class AnalysisViewModel : ViewModel() {
    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    private var engineClient: AndroidEngineClient? = null
    private var boardRecognizer: PieceRecognizer? = null
    private val recognizerReady = CompletableDeferred<Unit>()

    private fun log(msg: String) {
        _logs.value = _logs.value + msg
    }

    fun initOpenCV(context: Context) {
        log("OpenCV 初始化...")
        if (!OpenCVLoader.initDebug()) {
            log("OpenCV 初始化失败")
            _uiState.value = UiState.Error("OpenCV 初始化失败")
        } else {
            log("OpenCV 初始化成功")
        }
    }

    fun initEngine(context: Context) {
        log("初始化引擎...")
        val nnueFile = File(context.filesDir, "pikafish.nnue")
        if (!nnueFile.exists()) {
            try {
                log("解压 NNUE 权重文件...")
                context.assets.open("pikafish.nnue").use { input ->
                    nnueFile.outputStream().use { output -> input.copyTo(output) }
                }
                log("NNUE 权重文件解压完成")
            } catch (e: Exception) {
                log("NNUE 解压跳过: ${e.message}")
            }
        } else {
            log("NNUE 权重文件已存在")
        }
        val engine = AndroidEngineClient(context)
        log("启动引擎...")
        if (engine.start()) {
            engineClient = engine
            log("引擎启动成功")
        } else {
            log("引擎启动失败")
            _uiState.value = UiState.Error("引擎启动失败")
        }
    }

    fun initRecognizer(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val modelFile = File(context.cacheDir, "xiangqi_yolo.onnx")
                if (!modelFile.exists()) {
                    log("解压 ONNX 模型文件...")
                    context.assets.open("xiangqi_yolo.onnx").use { input ->
                        modelFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    log("ONNX 模型文件解压完成")
                } else {
                    log("ONNX 模型文件已存在")
                }
                log("加载 ONNX 模型...")
                boardRecognizer = YoloPieceRecognizer(modelFile.absolutePath)
                recognizerReady.complete(Unit)
                log("ONNX 模型加载完成")
            } catch (e: Exception) {
                log("识别模型加载失败: ${e.message}")
                recognizerReady.completeExceptionally(e)
                _uiState.value = UiState.Error("识别模型加载失败: ${e.message}")
            }
        }
    }

    fun analyze(context: Context, imageUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = UiState.Analyzing
            try {
                log("等待识别模型就绪...")
                recognizerReady.await()
                log("识别模型就绪")

                log("复制输入图片到缓存...")
                val tempFile = File(context.cacheDir, "input_${System.nanoTime()}.jpg")
                val inputStream = context.contentResolver.openInputStream(imageUri)
                if (inputStream == null) {
                    log("无法打开图片 URI")
                    _uiState.value = UiState.Error("无法打开图片")
                    return@launch
                }
                inputStream.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
                log("图片已保存到: ${tempFile.name}")

                log("开始棋盘识别...")
                val rawBoard = boardRecognizer!!.parseBoard(tempFile.absolutePath)
                log("棋盘识别完成")

                val board = fixBoardOrientation(rawBoard)
                log("方向修正完成")

                val validationWarnings = FenUtil.validatePositionDetails(board)
                if (validationWarnings.isEmpty()) {
                    log("局面验证通过")
                } else {
                    log("局面验证发现 ${validationWarnings.size} 个问题:")
                    validationWarnings.forEach { log("  ⚠ $it") }
                }

                log("生成 FEN...")
                val fen = FenUtil.toFen(board)
                log("FEN: $fen")

                log("引擎分析中...")
                val moves = engineClient?.getTopMoves(fen, 3, 10) ?: emptyList()
                log("引擎返回 ${moves.size} 条走法")
                val chineseMoves = moves.map { NotationConverter.convertToChineseNotation(board, it) }

                _uiState.value = UiState.Result(moves = chineseMoves, validationWarnings = validationWarnings)

                log("生成中间步骤预览图...")
                generatePreviews()
                log("分析完成")
            } catch (e: Exception) {
                log("分析出错: ${e.message ?: "未知错误"}")
                _uiState.value = UiState.Error(e.message ?: "分析出错")
            }
        }
    }

    private fun fixBoardOrientation(board: Array<Array<String?>>): Array<Array<String?>> {
        var blackTop = false
        for (i in 0..2) {
            for (j in 3..5) {
                if (board[i][j] == "bk") blackTop = true
            }
        }
        if (!blackTop) {
            for (i in board.indices) {
                for (j in board[i].indices) {
                    val p = board[i][j]
                    if (p != null) {
                        board[i][j] = if (p[0] == 'r') "b${p[1]}" else "r${p[1]}"
                    }
                }
            }
        }
        return board
    }

    private suspend fun generatePreviews() {
        val recognizer = boardRecognizer ?: return
        if (recognizer !is YoloPieceRecognizer) return

        val r = recognizer
        try {
            val step1Mat = BoardUtils.drawBoardRect(r.lastSrc, r.lastBoardRect)
            val step1Bmp = AndroidImageUtils.matToBitmap(step1Mat)
            val state1 = _uiState.value
            if (state1 is UiState.Result) {
                _uiState.value = state1.copy(stepPreviews = state1.stepPreviews + (1 to step1Bmp))
            }

            val step2Mat = BoardUtils.drawDetectionsOnly(
                r.lastSrc, r.lastBoardRect, r.lastDetections, r.lastGrid
            )
            val step2Bmp = AndroidImageUtils.matToBitmap(step2Mat)
            val state2 = _uiState.value
            if (state2 is UiState.Result) {
                _uiState.value = state2.copy(stepPreviews = state2.stepPreviews + (2 to step2Bmp))
            }

            val step3Mat = BoardUtils.drawPreview(
                r.lastSrc, r.lastBoardRect, r.lastDetections, r.lastGrid
            )
            val step3Bmp = AndroidImageUtils.matToBitmap(step3Mat)
            val state3 = _uiState.value
            if (state3 is UiState.Result) {
                _uiState.value = state3.copy(stepPreviews = state3.stepPreviews + (3 to step3Bmp))
            }

            val step6Mat = BoardUtils.drawPreview(
                r.lastSrc, r.lastBoardRect, emptyMap(), r.lastGrid
            )
            val step6Bmp = AndroidImageUtils.matToBitmap(step6Mat)
            val state4 = _uiState.value
            if (state4 is UiState.Result) {
                _uiState.value = state4.copy(stepPreviews = state4.stepPreviews + (6 to step6Bmp))
            }
        } catch (_: Exception) {
            // preview generation failure is non-fatal
        }
    }

    fun selectMove(index: Int) {
        val state = _uiState.value
        if (state is UiState.Result && index in state.moves.indices) {
            _uiState.value = state.copy(currentMoveIndex = index)
        }
    }

    override fun onCleared() {
        engineClient?.close()
        super.onCleared()
    }
}
