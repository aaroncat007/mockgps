package com.sofawander.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "routes")
data class RouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "points_json") val pointsJson: String,
    @ColumnInfo(name = "distance_meters") val distanceMeters: Double = 0.0,
    @ColumnInfo(name = "duration_ms") val durationMs: Long = 0L,
    @ColumnInfo(name = "location_summary") val locationSummary: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
