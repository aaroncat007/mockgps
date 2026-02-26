package com.sofawander.app.ui

data class FavoriteItem(
    val id: Long,
    val name: String,
    val lat: Double,
    val lng: Double,
    val locationDescription: String?,
    val note: String?
)
