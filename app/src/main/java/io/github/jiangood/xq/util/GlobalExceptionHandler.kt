package io.github.jiangood.xq.util

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GlobalExceptionHandler {
    private const val CRASH_FILE = "crash_log.txt"

    private val _exceptionEvent = MutableStateFlow<Throwable?>(null)
    val exceptionEvent: StateFlow<Throwable?> = _exceptionEvent

    private var crashFile: File? = null
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    fun init(context: Context) {
        crashFile = File(context.filesDir, CRASH_FILE)
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        checkSavedCrash()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            saveCrash(throwable)
            _exceptionEvent.value = throwable
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun report(throwable: Throwable) {
        AppLog.add("异常: ${throwable.message ?: "未知错误"}")
        _exceptionEvent.value = throwable
    }

    fun dismiss() {
        _exceptionEvent.value = null
        clearCrashFile()
    }

    private fun checkSavedCrash() {
        val file = crashFile ?: return
        if (!file.exists()) return
        try {
            val content = file.readText()
            if (content.isNotBlank()) {
                _exceptionEvent.value = RuntimeException("上次运行异常:\n$content")
            }
        } catch (_: Exception) {}
    }

    private fun saveCrash(throwable: Throwable) {
        val file = crashFile ?: return
        try {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            file.writeText("[$time] ${throwable.stackTraceToString()}", Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    private fun clearCrashFile() {
        try {
            crashFile?.writeText("")
        } catch (_: Exception) {}
    }
}
