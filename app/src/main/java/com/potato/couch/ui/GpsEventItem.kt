package com.potato.couch.ui

data class GpsEventItem(
    val timestamp: Long,
    val lat: Double,
    val lng: Double,
    val accuracy: Float,
    val speedMps: Double
)
