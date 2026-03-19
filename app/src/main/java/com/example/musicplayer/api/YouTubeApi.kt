package com.example.musicplayer.api

import com.google.gson.annotations.SerializedName
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface YouTubeApi {
    @GET("api/v1/search")
    fun search(
        @Query("q") query: String,
        @Query("type") type: String = "video"
    ): Call<List<InvidiousSearchResult>>

    @GET("api/v1/videos/{videoId}")
    fun getVideoInfo(
        @Path("videoId") videoId: String
    ): Call<InvidiousVideoInfo>
}

data class InvidiousSearchResult(
    @SerializedName("videoId") val videoId: String,
    @SerializedName("title") val title: String,
    @SerializedName("author") val author: String,
    @SerializedName("videoThumbnails") val thumbnails: List<Thumbnail>,
    @SerializedName("lengthSeconds") val duration: Int
)

data class Thumbnail(@SerializedName("url") val url: String)

data class InvidiousVideoInfo(
    @SerializedName("adaptiveFormats") val adaptiveFormats: List<AdaptiveFormat>
)

data class AdaptiveFormat(
    @SerializedName("url") val url: String,
    @SerializedName("type") val type: String,
    @SerializedName("bitrate") val bitrate: String
)
