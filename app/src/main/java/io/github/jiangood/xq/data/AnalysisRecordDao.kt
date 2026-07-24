package io.github.jiangood.xq.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AnalysisRecordDao {
    @Query("SELECT * FROM analysis_records ORDER BY timestamp DESC")
    fun getAllRecords(): Flow<List<AnalysisRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(record: AnalysisRecord): Long

    @Query("SELECT COUNT(*) FROM analysis_records")
    fun count(): Int

    @Query("DELETE FROM analysis_records WHERE id IN (SELECT id FROM analysis_records ORDER BY timestamp ASC LIMIT :count)")
    fun deleteOldest(count: Int)

    @Query("DELETE FROM analysis_records WHERE id = :id")
    fun deleteById(id: Long)

    @Query("SELECT screenshotPath FROM analysis_records WHERE screenshotPath IS NOT NULL")
    fun getAllScreenshotPaths(): List<String?>

    @Query("SELECT visualizationPath FROM analysis_records WHERE visualizationPath IS NOT NULL")
    fun getAllVisualizationPaths(): List<String?>
}
