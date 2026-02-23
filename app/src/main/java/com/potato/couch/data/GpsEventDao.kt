package com.potato.couch.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GpsEventDao {
    @Query("SELECT * FROM gps_events WHERE run_id = :runId ORDER BY timestamp ASC")
    fun getEventsForRun(runId: Long): Flow<List<GpsEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: GpsEventEntity): Long
}
