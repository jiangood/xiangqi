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
        val standardMoves: List<String>,
        val moves: List<String>,
        val currentMoveIndex: Int = 0,
        val stepPreviews: Map<Int, Bitmap> = emptyMap()
    ) : UiState()
    data class Error(val message: String) : UiState()
}

class AnalysisViewModel : ViewModel() {
    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState

    private var engineClient: AndroidEngineClient? = null
    private var boardRecognizer: PieceRecognizer? = null

    fun initOpenCV(context: Context) {
        if (!OpenCVLoader.initDebug()) {
            _uiState.value = UiState.Error("OpenCV 初始化失败")
        }
    }

    fun initEngine(context: Context) {
        // Extract NNUE weights file (data, not executable — goes in filesDir)
        val nnueFile = File(context.filesDir, "pikafish.nnue")
        if (!nnueFile.exists()) {
            try {
                context.assets.open("pikafish.nnue").use { input ->
                    nnueFile.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                // NNUE might be bundled elsewhere, skip
            }
        }
        // Engine binary comes from nativeLibraryDir (exec-allowed mount)
        // via jniLibs/arm64-v8a/libpikafish-armv8*.so
        val engine = AndroidEngineClient(context)
        if (engine.start()) {
            engineClient = engine
        } else {
            _uiState.value = UiState.Error("引擎启动失败")
        }
    }

    fun initRecognizer(context: Context) {
        try {
            val modelFile = File(context.cacheDir, "xiangqi_yolo.onnx")
            if (!modelFile.exists()) {
                context.assets.open("xiangqi_yolo.onnx").use { input ->
                    modelFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            boardRecognizer = YoloPieceRecognizer(modelFile.absolutePath)
        } catch (e: Exception) {
            _uiState.value = UiState.Error("识别模型加载失败: ${e.message}")
        }
    }

    fun analyze(context: Context, imageUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = UiState.Analyzing
            try {
                val tempFile = File(context.cacheDir, "input_${System.nanoTime()}.jpg")
                context.contentResolver.openInputStream(imageUri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
                val rawBoard = boardRecognizer!!.parseBoard(tempFile.absolutePath)

                val board = fixBoardOrientation(rawBoard)

                val fen = FenUtil.toFen(board)
                val moves = engineClient?.getTopMoves(fen, 3, 10) ?: emptyList()
                val chineseMoves = moves.map { NotationConverter.convertToChineseNotation(board, it) }

                _uiState.value = UiState.Result(standardMoves = moves, moves = chineseMoves)

                generatePreviews()
            } catch (e: Exception) {
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
            val step3Mat = BoardUtils.drawPreview(
                r.lastSrc, r.lastBoardRect, r.lastDetections, r.lastGrid
            )
            val step3Bmp = AndroidImageUtils.matToBitmap(step3Mat)
            val state = _uiState.value
            if (state is UiState.Result) {
                _uiState.value = state.copy(stepPreviews = state.stepPreviews + (3 to step3Bmp))
            }

            val step4Mat = BoardUtils.drawPreview(
                r.lastSrc, r.lastBoardRect, emptyMap(), r.lastGrid
            )
            val step4Bmp = AndroidImageUtils.matToBitmap(step4Mat)
val state2 = _uiState.value
                if (state2 is UiState.Result && state2.standardMoves.isNotEmpty()) {
                    // Draw red circle on source square of best move
                    val move = state2.standardMoves[0]
                    val col = move[0] - 'a'
                    val row = 9 - (move[1].digitToInt() - 1)
                    
                    val grid = r.lastGrid
                    if (grid.size >= 11 && grid[0].size >= 10 && row in 0..9 && col in 0..8) {
                        val x = (grid[row][col].x + grid[row][col + 1].x) / 2
                        val y = (grid[row][col].y + grid[row + 1][col].y) / 2
                        org.opencv.imgproc.Imgproc.circle(
                            step4Mat,
                            org.opencv.core.Point(x, y),
                            20,
                            org.opencv.core.Scalar(0.0, 0.0, 255.0),
                            3
                        )
                    }
                    
                    _uiState.value = state2.copy(stepPreviews = state2.stepPreviews + (4 to step4Bmp))
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
