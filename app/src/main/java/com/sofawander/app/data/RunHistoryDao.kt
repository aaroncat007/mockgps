package com.sofawander.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RunHistoryDao {
    @Query("SELECT * FROM run_history ORDER BY started_at DESC")
    fun getAllHistoryFlow(): Flow<List<RunHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(run: RunHistoryEntity): Long

    @Query("SELECT * FROM run_history WHERE id = :id")
    suspend fun getById(id: Long): RunHistoryEntity?

    @Query("UPDATE run_history SET ended_at = :endedAt, status = :status WHERE id = :id")
    suspend fun updateEnd(id: Long, endedAt: Long, status: String)

    @Query("UPDATE run_history SET route_name = :newName WHERE id = :id")
    suspend fun rename(id: Long, newName: String)

    @Query("DELETE FROM run_history WHERE id = :id")
    suspend fun deleteById(id: Long)
}
