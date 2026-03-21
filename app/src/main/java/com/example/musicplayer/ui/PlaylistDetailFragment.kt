package com.example.musicplayer.ui

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
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
import java.io.File
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

    private val playbackStateListener: (Boolean) -> Unit = {
        songAdapter.notifyDataSetChanged()
    }

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
                val track = song.toTrack()
                val currentPlayingId = PlayerManager.currentQueue.getOrNull(PlayerManager.currentIndex)?.id
                
                if (track.id == currentPlayingId) {
                    PlayerManager.togglePlayPause()
                } else {
                    val localUri = findLocalUri(track.name)
                    if (localUri != null) {
                        play(songList.map { it.toTrack() }, localUri, index)
                    } else {
                        resolveAndPlay(track) { url ->
                            play(songList.map { it.toTrack() }, url, index)
                        }
                    }
                }
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

        binding.btnModeSequential.setOnClickListener { setPlayMode(PlayMode.SEQUENTIAL) }
        binding.btnModeShuffle.setOnClickListener { setPlayMode(PlayMode.SHUFFLE) }
        
        binding.btnPlayAll.setOnClickListener {
            if (songList.isEmpty()) return@setOnClickListener
            val track = songList[0].toTrack()
            val localUri = findLocalUri(track.name)
            if (localUri != null) {
                play(songList.map { it.toTrack() }, localUri, 0)
            } else {
                resolveAndPlay(track) { url ->
                    play(songList.map { it.toTrack() }, url, 0)
                }
            }
        }

        PlayerManager.addPlaybackStateListener(playbackStateListener)

        loadPlaylistSongs()
        updatePlayModeUI()
    }

    fun updatePlayingPosition(index: Int) {
        if (isAdded) {
            songAdapter.setPlayingPosition(index)
        }
    }

    private fun setPlayMode(mode: PlayMode) {
        PlayerManager.playMode = mode
        updatePlayModeUI()
    }

    private fun updatePlayModeUI() {
        val selectedColor = android.graphics.Color.parseColor("#7C6FFF")
        val inactiveColor = android.graphics.Color.parseColor("#22223A")
        val isSeq = PlayerManager.playMode == PlayMode.SEQUENTIAL

        binding.btnModeSequential.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(if (isSeq) selectedColor else inactiveColor)
            setTextColor(if (isSeq) android.graphics.Color.WHITE else android.graphics.Color.parseColor("#9999BB"))
            text = if (isSeq) "✓ SIRALI" else "SIRALI"
        }
        binding.btnModeShuffle.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(if (!isSeq) selectedColor else inactiveColor)
            setTextColor(if (!isSeq) android.graphics.Color.WHITE else android.graphics.Color.parseColor("#9999BB"))
            text = if (!isSeq) "✓ KARIŞIK" else "🔀 KARIŞIK"
        }
    }

    private fun play(tracks: List<Track>, url: String, index: Int) {
        PlayerManager.urlResolver = { _, cb -> cb(url) }
        PlayerManager.playQueue(tracks, index)
        songAdapter.setPlayingPosition(index)
    }

    private fun findLocalUri(name: String): String? {
        val ctx = context ?: return null
        val safeName = name.take(50).replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val projection = arrayOf(MediaStore.MediaColumns._ID)
                val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
                val args = arrayOf("%$safeName%")
                ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, args, null)?.use { c ->
                    if (c.moveToFirst()) return android.content.ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0)).toString()
                }
            } else {
                val mp3 = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Melodify/$safeName.mp3")
                if (mp3.exists()) return mp3.absolutePath
            }
        } catch (e: Exception) {}
        return null
    }

    fun loadPlaylistSongs() {
        if (playlistId == -1L || !isAdded) return
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val db = AppDatabase.getInstance(requireContext())
            db.playlistSongDao().getSongsInPlaylist(playlistId).collect { songs ->
                songList = songs
                songAdapter.submitList(songs)
                binding.tvSongCount.text = "${songs.size} şarkı"
                binding.emptyView.visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun setupRetrofit() {
        val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).build()
        youtubeApi = Retrofit.Builder().baseUrl("http://77.92.154.224:5050/").client(client).addConverterFactory(GsonConverterFactory.create()).build().create(YouTubeApi::class.java)
    }

    private fun resolveAndPlay(track: Track, callback: (String) -> Unit) {
        youtubeApi.getVideoInfo(track.id).enqueue(object : Callback<InvidiousVideoInfo> {
            override fun onResponse(call: Call<InvidiousVideoInfo>, response: Response<InvidiousVideoInfo>) {
                response.body()?.url?.let { callback(it) }
            }
            override fun onFailure(call: Call<InvidiousVideoInfo>, t: Throwable) {}
        })
    }

    private fun PlaylistSongEntity.toTrack() = Track(videoId, title, author, thumbnail, "", duration)

    override fun onDestroyView() {
        super.onDestroyView()
        PlayerManager.removePlaybackStateListener(playbackStateListener)
        _binding = null
    }
}
