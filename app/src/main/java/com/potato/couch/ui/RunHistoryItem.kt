package com.potato.couch.ui

data class RunHistoryItem(
    val id: Long,
    val routeName: String?,
    val pointCount: Int,
    val speedMode: Int,
    val loopEnabled: Boolean,
    val roundTripEnabled: Boolean,
    val startedAt: Long,
    val endedAt: Long?,
    val status: String
)
