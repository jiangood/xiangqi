package io.github.jiangood.xq.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jiangood.xq.analysis.AnalysisEngine
import io.github.jiangood.xq.opencv.BoardUtils
import io.github.jiangood.xq.opencv.YoloPieceRecognizer
import io.github.jiangood.xq.platform.AndroidImageUtils
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

    fun initOpenCV(context: Context) {
        if (!OpenCVLoader.initDebug()) {
            _uiState.value = UiState.Error("OpenCV 初始化失败")
        }
    }

    fun initAnalysisEngine(context: Context) {
        viewModelScope.launch {
            AnalysisEngine.init(context)
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
                val result = AnalysisEngine.analyze(context, tempFile)
                if (result != null) {
                    _uiState.value = UiState.Result(
                        standardMoves = result.standardMoves,
                        moves = result.chineseMoves
                    )
                    generatePreviews()
                } else {
                    _uiState.value = UiState.Error("分析失败")
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "分析出错")
            }
        }
    }

    private suspend fun generatePreviews() {
        val recognizer = AnalysisEngine.boardRecognizer ?: return
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
        } catch (_: Exception) {}
    }

    fun selectMove(index: Int) {
        val state = _uiState.value
        if (state is UiState.Result && index in state.moves.indices) {
            _uiState.value = state.copy(currentMoveIndex = index)
        }
    }
}
