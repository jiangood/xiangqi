package io.github.jiangood.xq.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.jiangood.xq.analysis.AnalysisEngine
import io.github.jiangood.xq.analysis.AnalysisEventBus
import io.github.jiangood.xq.analysis.AnalysisResult
import io.github.jiangood.xq.data.AnalysisRecord
import io.github.jiangood.xq.data.AppDatabase
import io.github.jiangood.xq.platform.AndroidImageUtils
import io.github.jiangood.xq.service.CaptureOverlayService
import io.github.jiangood.xq.util.AppLog
import io.github.jiangood.xq.util.FenUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
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
        val imageDir: String? = null,
        val sourceImageDir: String? = null
    ) : UiState()
    data class Error(
        val message: String,
        val sourceImageDir: String? = null
    ) : UiState()
}

class AnalysisViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    private val _showNnueWarning = MutableStateFlow(false)
    val showNnueWarning: StateFlow<Boolean> = _showNnueWarning

    val logs: StateFlow<List<String>> = AppLog.logs

    val history: StateFlow<List<AnalysisRecord>> = AppDatabase.getInstance(getApplication())
        .analysisRecordDao()
        .getAllRecords()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // 订阅悬浮窗分析事件
        viewModelScope.launch {
            AnalysisEventBus.events.collect { event ->
                when (event) {
                    is AnalysisEventBus.BusEvent.Result -> {
                        val result = event.result
                        val validationWarnings = FenUtil.validatePositionDetails(result.board)
                        _uiState.value = UiState.Result(
                            moves = result.chineseMoves,
                            standardMoves = result.standardMoves,
                            validationWarnings = validationWarnings,
                            elapsedMs = event.elapsedMs,
                            imageDir = result.visualizationPath,
                            sourceImageDir = result.sourceImagePath
                        )
                    }
                    is AnalysisEventBus.BusEvent.Error -> {
                        _uiState.value = UiState.Error(event.message, event.screenshotPath)
                    }
                    is AnalysisEventBus.BusEvent.Analyzing -> {
                        _uiState.value = UiState.Analyzing
                    }
                    is AnalysisEventBus.BusEvent.CapturedScreenshot -> {
                        // 可忽略
                    }
                }
            }
        }

        // 加载上次悬浮窗存在的分析结果
        CaptureOverlayService.lastAnalysisResult?.let { result ->
            val validationWarnings = FenUtil.validatePositionDetails(result.board)
            _uiState.value = UiState.Result(
                moves = result.chineseMoves,
                standardMoves = result.standardMoves,
                validationWarnings = validationWarnings,
                imageDir = result.visualizationPath,
                sourceImageDir = result.sourceImagePath
            )
        }
    }

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

    /**
     * 选图入口：从 URI 拷贝到缓存后分析
     */
    fun analyze(context: Context, imageUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = UiState.Analyzing
            var tempFile: File? = null
            try {
                AppLog.add("清理旧缓存...")
                AndroidImageUtils.cleanupOldAnalysisDirs(context.cacheDir)
                AppLog.add("等待引擎与识别模型就绪...")
                val waitStart = System.currentTimeMillis()
                AnalysisEngine.awaitInitialized()
                AppLog.add("引擎与识别模型就绪 (等待 ${System.currentTimeMillis() - waitStart}ms)")

                AppLog.add("复制输入图片到缓存...")
                val copyStart = System.currentTimeMillis()
                val inputFile = File(context.cacheDir, "input_${System.nanoTime()}.jpg")
                tempFile = inputFile
                val inputStream = context.contentResolver.openInputStream(imageUri)
                if (inputStream == null) {
                    AppLog.add("无法打开图片 URI")
                    _uiState.value = UiState.Error("无法打开图片")
                    return@launch
                }
                AndroidImageUtils.copyToFile(inputStream, inputFile)
                AppLog.add("图片已保存到: ${inputFile.name} (${System.currentTimeMillis() - copyStart}ms)")

                runAnalysis(inputFile)
            } catch (e: Exception) {
                AppLog.add("分析出错: ${e.message ?: "未知错误"}")
                _uiState.value = UiState.Error(
                    e.message ?: "分析出错",
                    tempFile?.absolutePath
                )
            }
        }
    }

    /**
     * 核心分析逻辑：对给定的文件执行分析并更新 UI 状态
     */
    private suspend fun runAnalysis(imageFile: File) {
        _uiState.value = UiState.Analyzing
        try {
            AnalysisEngine.awaitInitialized()

            AppLog.add("开始分析图片...")
            val analyzeStart = System.currentTimeMillis()
            val result = AnalysisEngine.analyze(imageFile)
            val analyzeElapsed = System.currentTimeMillis() - analyzeStart
            AppLog.add("分析总耗时: ${analyzeElapsed}ms")

            if (result == null) {
                AppLog.add("分析返回 null")
                _uiState.value = UiState.Error("分析失败", imageFile.absolutePath)
                return
            }

            val validationWarnings = FenUtil.validatePositionDetails(result.board)

            _uiState.value = UiState.Result(
                moves = result.chineseMoves,
                standardMoves = result.standardMoves,
                validationWarnings = validationWarnings,
                elapsedMs = analyzeElapsed,
                imageDir = result.visualizationPath,
                sourceImageDir = result.sourceImagePath
            )

            AppLog.add("分析完成")
        } catch (e: Exception) {
            AppLog.add("分析出错: ${e.message ?: "未知错误"}")
            _uiState.value = UiState.Error(
                e.message ?: "分析出错",
                imageFile.absolutePath
            )
        }
    }

    fun dismissNnueWarning() {
        _showNnueWarning.value = false
    }

    fun selectMove(@Suppress("UNUSED_PARAMETER") index: Int) {
        // single move only, no-op
    }
}
