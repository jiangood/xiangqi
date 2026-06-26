package io.github.jiangood.xq.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object AppLog {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    fun add(msg: String) {
        _logs.value = _logs.value + msg
    }

    fun clear() {
        _logs.value = emptyList()
    }
}
