package com.example.musicplayer.api

import com.google.gson.annotations.SerializedName
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface YouTubeApi {
    @GET("search")
    fun search(
        @Query("q") query: String,
        @Query("count") count: Int = 20,
        @Query("page") page: Int = 1
    ): Call<SearchResponse>

    @GET("stream/{videoId}")
    fun getVideoInfo(
        @Path("videoId") videoId: String
    ): Call<InvidiousVideoInfo>

    @GET("playlist/{playlistId}")
    fun getPlaylist(
        @Path("playlistId") playlistId: String
    ): Call<PlaylistInfoResponse>
}

data class SearchResponse(
    @SerializedName("tracks") val tracks: List<InvidiousSearchResult>,
    @SerializedName("page") val page: Int,
    @SerializedName("has_more") val hasMore: Boolean
)

data class InvidiousSearchResult(
    @SerializedName("id") val videoId: String,
    @SerializedName("title") val title: String,
    @SerializedName("author") val author: String,
    @SerializedName("thumbnail") val thumbnails: String,
    @SerializedName("duration") val duration: Int,
    @SerializedName("type") val type: String? = "video" // "video" veya "playlist"
)

data class InvidiousVideoInfo(
    @SerializedName("url") val url: String
)

data class PlaylistInfoResponse(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("author") val author: String,
    @SerializedName("thumbnail") val thumbnail: String,
    @SerializedName("tracks") val tracks: List<InvidiousSearchResult>
)
