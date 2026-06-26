package io.github.jiangood.xq.util

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private const val MAX_LOG_LINES = 500
    private const val LOG_FILE = "app_logs.txt"

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    private var logFile: File? = null

    fun init(context: Context) {
        logFile = File(context.filesDir, LOG_FILE)
        try {
            val saved = logFile?.readLines() ?: emptyList()
            if (saved.isNotEmpty()) {
                _logs.value = saved.takeLast(MAX_LOG_LINES)
            }
        } catch (_: Exception) {}
    }

    fun add(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$time] $msg"
        val current = _logs.value
        val updated = (current + line).takeLast(MAX_LOG_LINES)
        _logs.value = updated
        try {
            logFile?.appendText("$line\n", Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    fun clear() {
        _logs.value = emptyList()
        try {
            logFile?.writeText("")
        } catch (_: Exception) {}
    }
}
