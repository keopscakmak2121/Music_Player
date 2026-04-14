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
    @SerializedName("id") val id: String?,
    @SerializedName("videoId") val videoId: String?,
    @SerializedName("playlistId") val playlistId: String?,
    @SerializedName("title") val title: String?,
    @SerializedName("author") val author: String?,
    @SerializedName("thumbnail") val thumbnail: String?,
    @SerializedName("thumbnails") val thumbnails: String?, // Bazı API'lar thumbnails kullanıyor
    @SerializedName("duration") val duration: Int = 0,
    @SerializedName("type") val type: String? = "video"
) {
    // Hangisi doluysa onu döndüren yardımcı fonksiyonlar
    val realId: String get() = playlistId ?: videoId ?: id ?: ""
    val realThumbnail: String get() = thumbnail ?: thumbnails ?: ""
}

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
