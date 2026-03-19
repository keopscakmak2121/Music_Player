package com.example.musicplayer.api

import com.example.musicplayer.model.JamendoResponse
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Query

interface JamendoApi {
    @GET("tracks/")
    fun searchTracks(
        @Query("client_id") clientId: String = "56d30c3a", 
        @Query("format") format: String = "json",
        @Query("namesearch") query: String, // 'search' yerine 'namesearch' daha kapsamlıdır
        @Query("limit") limit: Int = 30,
        @Query("order") order: String = "popularity_total" // En popülerleri getir
    ): Call<JamendoResponse>
}
