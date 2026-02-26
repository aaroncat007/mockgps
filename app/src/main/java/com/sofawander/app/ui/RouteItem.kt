package com.sofawander.app.ui

import com.sofawander.app.data.RoutePoint

data class RouteItem(
    val id: Long,
    val name: String,
    val points: List<RoutePoint>,
    val distanceMeters: Double = 0.0,
    val durationMs: Long = 0L,
    val locationSummary: String? = null,
    val createdAt: Long
)
