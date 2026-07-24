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
    suspend fun insert(record: AnalysisRecord): Long

    @Query("SELECT COUNT(*) FROM analysis_records")
    suspend fun count(): Int

    @Query("DELETE FROM analysis_records WHERE id IN (SELECT id FROM analysis_records ORDER BY timestamp ASC LIMIT :count)")
    suspend fun deleteOldest(count: Int)

    @Query("DELETE FROM analysis_records WHERE id = :id")
    suspend fun deleteById(id: Long)
}
