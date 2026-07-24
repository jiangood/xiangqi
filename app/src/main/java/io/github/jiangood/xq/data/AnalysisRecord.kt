package io.github.jiangood.xq.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "analysis_records")
data class AnalysisRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val status: String,
    val move: String? = null,
    val fen: String? = null,
    val pieceCount: Int? = null,
    val elapsedMs: Long? = null,
    val errorMessage: String? = null,
    val screenshotPath: String? = null,
    val visualizationPath: String? = null,
    val logs: String = ""
)
