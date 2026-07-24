package io.github.jiangood.xq.analysis

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object AnalysisEventBus {

    sealed class BusEvent {
        data class Analyzing(val message: String = "分析中...") : BusEvent()
        data class Result(
            val result: AnalysisResult,
            val elapsedMs: Long
        ) : BusEvent()
        data class Error(
            val message: String,
            val screenshotPath: String? = null
        ) : BusEvent()
        data class CapturedScreenshot(val filePath: String) : BusEvent()
    }

    private val _events = MutableSharedFlow<BusEvent>(replay = 0, extraBufferCapacity = 4)
    val events: SharedFlow<BusEvent> = _events.asSharedFlow()

    fun publish(event: BusEvent) {
        _events.tryEmit(event)
    }
}
