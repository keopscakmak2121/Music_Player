package com.example.musicplayer.model

import com.google.gson.annotations.SerializedName

data class Track(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("artist_name") val artistName: String,
    @SerializedName("image") val image: String,
    @SerializedName("audio") val audio: String,
    @SerializedName("duration") val duration: Int,
    var videoId: String = "" // Invidious/YouTube search results use this
)
