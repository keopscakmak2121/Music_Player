package com.example.musicplayer

import android.app.DownloadManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.musicplayer.api.YouTubeApi
import com.example.musicplayer.api.InvidiousSearchResult
import com.example.musicplayer.api.InvidiousVideoInfo
import com.example.musicplayer.databinding.ActivityMainBinding
import com.example.musicplayer.model.Track
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var youtubeApi: YouTubeApi
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController?
        get() = if (controllerFuture?.isDone == true) controllerFuture?.get() else null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRetrofit()
        setupRecyclerView()
        setupMediaController()

        binding.btnSearch.setOnClickListener {
            val query = binding.etSearch.text.toString()
            if (query.isNotEmpty()) {
                searchTracks(query)
            }
        }
    }

    private fun setupRetrofit() {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl("https://invidious.flokinet.to/") // Daha sağlam sunucu
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        youtubeApi = retrofit.create(YouTubeApi::class.java)
    }

    private fun setupRecyclerView() {
        binding.rvTracks.layoutManager = LinearLayoutManager(this)
    }

    private fun setupMediaController() {
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            binding.playerView.player = controller
        }, MoreExecutors.directExecutor())
    }

    private fun searchTracks(query: String) {
        binding.btnSearch.isEnabled = false
        youtubeApi.search(query).enqueue(object : Callback<List<InvidiousSearchResult>> {
            override fun onResponse(call: Call<List<InvidiousSearchResult>>, response: Response<List<InvidiousSearchResult>>) {
                binding.btnSearch.isEnabled = true
                if (response.isSuccessful) {
                    val results = response.body() ?: emptyList()
                    val tracks = results.map { 
                        Track(it.videoId, it.title, it.author, it.thumbnails.firstOrNull()?.url ?: "", "", it.duration)
                    }
                    binding.rvTracks.adapter = TrackAdapter(
                        tracks,
                        onTrackClick = { getStreamAndPlay(it) },
                        onDownloadClick = { getStreamAndDownload(it) }
                    )
                } else {
                    Toast.makeText(this@MainActivity, "Hata kodu: ${response.code()}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<List<InvidiousSearchResult>>, t: Throwable) {
                binding.btnSearch.isEnabled = true
                Toast.makeText(this@MainActivity, "Bağlantı Hatası: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun getStreamAndPlay(track: Track) {
        youtubeApi.getVideoInfo(track.id).enqueue(object : Callback<InvidiousVideoInfo> {
            override fun onResponse(call: Call<InvidiousVideoInfo>, response: Response<InvidiousVideoInfo>) {
                val streamUrl = response.body()?.adaptiveFormats?.firstOrNull { it.type.contains("audio") }?.url ?: return
                playTrack(track, streamUrl)
            }
            override fun onFailure(call: Call<InvidiousVideoInfo>, t: Throwable) {}
        })
    }

    private fun getStreamAndDownload(track: Track) {
        youtubeApi.getVideoInfo(track.id).enqueue(object : Callback<InvidiousVideoInfo> {
            override fun onResponse(call: Call<InvidiousVideoInfo>, response: Response<InvidiousVideoInfo>) {
                val streamUrl = response.body()?.adaptiveFormats?.firstOrNull { it.type.contains("audio") }?.url ?: return
                downloadTrack(track, streamUrl)
            }
            override fun onFailure(call: Call<InvidiousVideoInfo>, t: Throwable) {}
        })
    }

    private fun playTrack(track: Track, url: String) {
        val controller = controller ?: return
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMediaId(track.id)
            .build()
        controller.setMediaItem(mediaItem)
        controller.prepare()
        controller.play()
        Toast.makeText(this, "Çalınıyor: ${track.name}", Toast.LENGTH_SHORT).show()
    }

    private fun downloadTrack(track: Track, url: String) {
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(track.name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "${track.name}.mp3")

        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadManager.enqueue(request)
        Toast.makeText(this, "İndirme başlatıldı", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}
