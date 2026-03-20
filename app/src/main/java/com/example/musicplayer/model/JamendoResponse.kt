package com.example.musicplayer.model

import com.google.gson.annotations.SerializedName

data class JamendoResponse(
    @SerializedName("results") val results: List<Track>
)
