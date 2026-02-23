package com.potato.couch.ui

data class FavoriteItem(
    val id: Long,
    val name: String,
    val lat: Double,
    val lng: Double,
    val note: String?
)
