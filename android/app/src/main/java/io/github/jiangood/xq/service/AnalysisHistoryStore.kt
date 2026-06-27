package io.github.jiangood.xq.service

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryEntry(
    val fen: String,
    val chineseMove: String,
    val standardMove: String,
    val timestamp: Long
)

object AnalysisHistoryStore {
    private const val MAX_ENTRIES = 50
    private const val FILE_NAME = "analysis_history.json"

    private fun getFile(context: Context): File =
        File(context.filesDir, FILE_NAME)

    fun addEntry(context: Context, entry: HistoryEntry) {
        val entries = loadAll(context).toMutableList()
        entries.add(entry)
        val trimmed = entries.takeLast(MAX_ENTRIES)
        saveAll(context, trimmed)
    }

    fun getEntries(context: Context): List<HistoryEntry> {
        return loadAll(context).reversed()
    }

    fun clear(context: Context) {
        getFile(context).delete()
    }

    private fun loadAll(context: Context): MutableList<HistoryEntry> {
        val file = getFile(context)
        if (!file.exists()) return mutableListOf()
        return try {
            val json = JSONObject(file.readText())
            val arr = json.getJSONArray("entries")
            val list = mutableListOf<HistoryEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    HistoryEntry(
                        fen = obj.getString("fen"),
                        chineseMove = obj.getString("chineseMove"),
                        standardMove = obj.getString("standardMove"),
                        timestamp = obj.getLong("timestamp")
                    )
                )
            }
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun saveAll(context: Context, entries: List<HistoryEntry>) {
        val arr = JSONArray()
        entries.forEach { entry ->
            arr.put(
                JSONObject().apply {
                    put("fen", entry.fen)
                    put("chineseMove", entry.chineseMove)
                    put("standardMove", entry.standardMove)
                    put("timestamp", entry.timestamp)
                }
            )
        }
        val json = JSONObject().apply {
            put("entries", arr)
        }
        getFile(context).writeText(json.toString())
    }
}
