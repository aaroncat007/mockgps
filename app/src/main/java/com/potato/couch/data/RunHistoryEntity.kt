package com.potato.couch.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "run_history")
data class RunHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "route_name") val routeName: String?,
    @ColumnInfo(name = "point_count") val pointCount: Int,
    @ColumnInfo(name = "speed_mode") val speedMode: Int,
    @ColumnInfo(name = "loop_enabled") val loopEnabled: Boolean,
    @ColumnInfo(name = "roundtrip_enabled") val roundTripEnabled: Boolean,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long? = null,
    @ColumnInfo(name = "status") val status: String
)
