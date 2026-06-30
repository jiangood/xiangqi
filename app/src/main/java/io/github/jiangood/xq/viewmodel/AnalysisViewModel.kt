package io.github.jiangood.xq.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jiangood.xq.analysis.AnalysisEngine
import io.github.jiangood.xq.platform.AndroidImageUtils
import io.github.jiangood.xq.util.AppLog
import io.github.jiangood.xq.util.FenUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
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

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    private val _showNnueWarning = MutableStateFlow(false)
    val showNnueWarning: StateFlow<Boolean> = _showNnueWarning

    val logs: StateFlow<List<String>> = AppLog.logs

    fun initOpenCV(context: Context) {
        AppLog.add("OpenCV 初始化...")
        if (!OpenCVLoader.initLocal()) {
            AppLog.add("OpenCV 初始化失败")
            _uiState.value = UiState.Error("OpenCV 初始化失败")
        } else {
            AppLog.add("OpenCV 初始化成功")
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    AnalysisEngine.awaitInitialized()
                    _ready.value = AnalysisEngine.boardRecognizer != null && AnalysisEngine.engineClient != null
                    if (AnalysisEngine.isNnueMissing) {
                        _showNnueWarning.value = true
                    }
                } catch (_: Exception) {
                    _ready.value = false
                }
            }
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

                AppLog.add("复制输入图片到缓存...")
                val tempFile = File(context.cacheDir, "input_${System.nanoTime()}.jpg")
                val inputStream = context.contentResolver.openInputStream(imageUri)
                if (inputStream == null) {
                    AppLog.add("无法打开图片 URI")
                    _uiState.value = UiState.Error("无法打开图片")
                    return@launch
                }
                AndroidImageUtils.copyToFile(inputStream, tempFile)
                AppLog.add("图片已保存到: ${tempFile.name}")

                val result = AnalysisEngine.analyze(tempFile)
                if (result == null) {
                    _uiState.value = UiState.Error("分析失败")
                    return@launch
                }

                val validationWarnings = FenUtil.validatePositionDetails(result.board)
                val elapsedMs = 0L

                _uiState.value = UiState.Result(
                    moves = result.chineseMoves,
                    standardMoves = result.standardMoves,
                    validationWarnings = validationWarnings,
                    elapsedMs = elapsedMs,
                    imageDir = result.visualizationPath
                )

                AppLog.add("分析完成")
            } catch (e: Exception) {
                AppLog.add("分析出错: ${e.message ?: "未知错误"}")
                _uiState.value = UiState.Error(e.message ?: "分析出错")
            }
        }
    }

    fun dismissNnueWarning() {
        _showNnueWarning.value = false
    }

    fun selectMove(@Suppress("UNUSED_PARAMETER") index: Int) {
        // single move only, no-op
    }
}
