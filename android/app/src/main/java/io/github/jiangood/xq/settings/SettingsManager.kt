package io.github.jiangood.xq.settings

import android.content.Context
import android.content.SharedPreferences

object SettingsManager {
    private const val PREFS_NAME = "xq_settings"
    private const val KEY_DEPTH = "search_depth"
    private const val KEY_THREADS = "num_threads"
    private const val DEFAULT_DEPTH = 15
    private const val DEFAULT_THREADS = 4

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getDepth(): Int = prefs?.getInt(KEY_DEPTH, DEFAULT_DEPTH) ?: DEFAULT_DEPTH
    fun getThreads(): Int = prefs?.getInt(KEY_THREADS, DEFAULT_THREADS) ?: DEFAULT_THREADS

    fun setDepth(depth: Int) {
        prefs?.edit()?.putInt(KEY_DEPTH, depth)?.apply()
    }

    fun setThreads(threads: Int) {
        prefs?.edit()?.putInt(KEY_THREADS, threads)?.apply()
    }
}
