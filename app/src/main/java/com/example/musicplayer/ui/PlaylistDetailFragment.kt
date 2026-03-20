package com.example.musicplayer.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.musicplayer.PlayerManager
import com.example.musicplayer.PlayMode
import com.example.musicplayer.adapter.PlaylistSongAdapter
import com.example.musicplayer.api.InvidiousVideoInfo
import com.example.musicplayer.api.YouTubeApi
import com.example.musicplayer.databinding.FragmentPlaylistDetailBinding
import com.example.musicplayer.db.AppDatabase
import com.example.musicplayer.db.PlaylistSongEntity
import com.example.musicplayer.model.Track
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class PlaylistDetailFragment : Fragment() {

    private var _binding: FragmentPlaylistDetailBinding? = null
    private val binding get() = _binding!!

    var playlistId: Long = -1
        set(value) {
            field = value
            if (isAdded) loadPlaylistSongs()
        }
        
    var playlistName: String = ""
        set(value) {
            field = value
            if (isAdded) binding.tvPlaylistName.text = value
        }

    var onBack: (() -> Unit)? = null
    var onTrackSelected: ((Track, String) -> Unit)? = null

    private lateinit var youtubeApi: YouTubeApi
    private lateinit var songAdapter: PlaylistSongAdapter
    private var songList: List<PlaylistSongEntity> = emptyList()
    private var loadJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPlaylistDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRetrofit()
        
        binding.tvPlaylistName.text = playlistName
        binding.btnBack.setOnClickListener { onBack?.invoke() }

        songAdapter = PlaylistSongAdapter(
            onClick = { song, index ->
                val tracks = songList.map { it.toTrack() }
                PlayerManager.urlResolver = { track, cb -> resolveAndPlay(track, cb) }
                PlayerManager.playQueue(tracks, index)
            },
            onRemove = { song ->
                lifecycleScope.launch {
                    AppDatabase.getInstance(requireContext()).playlistSongDao().deleteSong(song)
                    Toast.makeText(requireContext(), "Kaldırıldı", Toast.LENGTH_SHORT).show()
                }
            }
        )

        binding.rvSongs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSongs.adapter = songAdapter

        binding.btnPlayAll.setOnClickListener {
            if (songList.isEmpty()) return@setOnClickListener
            PlayerManager.playMode = PlayMode.SEQUENTIAL
            val tracks = songList.map { it.toTrack() }
            PlayerManager.urlResolver = { track, cb -> resolveAndPlay(track, cb) }
            PlayerManager.playQueue(tracks, 0)
        }

        binding.btnShuffle.setOnClickListener {
            if (songList.isEmpty()) return@setOnClickListener
            PlayerManager.playMode = PlayMode.SHUFFLE
            val tracks = songList.map { it.toTrack() }
            PlayerManager.urlResolver = { track, cb -> resolveAndPlay(track, cb) }
            PlayerManager.playQueue(tracks, 0)
        }

        loadPlaylistSongs()
    }

    fun loadPlaylistSongs() {
        if (playlistId == -1L) return
        
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val db = AppDatabase.getInstance(requireContext())
            db.playlistSongDao().getSongsInPlaylist(playlistId).collect { songs ->
                songList = songs
                songAdapter.submitList(songs)
                binding.tvSongCount.text = "${songs.size} şarkı"
                binding.emptyView.visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE
                binding.rvSongs.visibility = if (songs.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun setupRetrofit() {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        youtubeApi = Retrofit.Builder()
            .baseUrl("http://77.92.154.224:5050/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(YouTubeApi::class.java)
    }

    private fun isLocalFile(id: String): Boolean {
        return id.startsWith("/") ||
               (id.length > 2 && id[1] == ':') ||
               id.endsWith(".mp3") ||
               id.endsWith(".m4a") ||
               id.endsWith(".webm")
    }

    private fun resolveAndPlay(track: Track, callback: ((String) -> Unit)? = null) {
        if (isLocalFile(track.id)) {
            val url = track.id
            if (callback != null) callback(url)
            else onTrackSelected?.invoke(track, url)
            return
        }
        youtubeApi.getVideoInfo(track.id).enqueue(object : Callback<InvidiousVideoInfo> {
            override fun onResponse(call: Call<InvidiousVideoInfo>, response: Response<InvidiousVideoInfo>) {
                val url = response.body()?.url ?: return
                if (callback != null) callback(url)
                else onTrackSelected?.invoke(track, url)
            }
            override fun onFailure(call: Call<InvidiousVideoInfo>, t: Throwable) {
                Toast.makeText(requireContext(), "Hata: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun PlaylistSongEntity.toTrack() = Track(videoId, title, author, thumbnail, "", duration)

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
