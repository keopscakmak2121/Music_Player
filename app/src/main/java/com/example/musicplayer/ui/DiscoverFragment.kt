package com.example.musicplayer.ui

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.musicplayer.PlayerManager
import com.example.musicplayer.PlayMode
import com.example.musicplayer.TrackAdapter
import com.example.musicplayer.api.InvidiousVideoInfo
import com.example.musicplayer.api.SearchResponse
import com.example.musicplayer.api.YouTubeApi
import com.example.musicplayer.databinding.FragmentDiscoverBinding
import com.example.musicplayer.db.AppDatabase
import com.example.musicplayer.db.PlaylistSongEntity
import com.example.musicplayer.model.Track
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

class DiscoverFragment : Fragment() {

    private var _binding: FragmentDiscoverBinding? = null
    private val binding get() = _binding!!
    private lateinit var youtubeApi: YouTubeApi
    private var trackAdapter: TrackAdapter? = null
    private var currentTracks: MutableList<Track> = mutableListOf()

    private val handler = Handler(Looper.getMainLooper())
    private val activeDownloads = mutableSetOf<Long>()

    private var currentQuery = ""
    private var currentPage = 1
    private var isLoadingMore = false
    private var hasMore = false

    companion object {
        const val BASE_URL = "http://77.92.154.224:5050/"
    }

    var onTrackSelected: ((Track, String) -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDiscoverBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRetrofit()
        binding.rvTracks.layoutManager = LinearLayoutManager(requireContext())
        binding.btnSearch.setOnClickListener { doSearch() }
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false
        }
        binding.btnModeSequential.setOnClickListener { setPlayMode(PlayMode.SEQUENTIAL) }
        binding.btnModeShuffle.setOnClickListener { setPlayMode(PlayMode.SHUFFLE) }
        binding.btnPlayAll.setOnClickListener {
            if (currentTracks.isEmpty()) return@setOnClickListener
            PlayerManager.urlResolver = { track, cb -> resolveAndPlay(track, cb) }
            PlayerManager.playQueue(currentTracks, 0)
        }

        // Scroll ile daha fazla yükle
        binding.rvTracks.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                val total = layoutManager.itemCount
                if (!isLoadingMore && hasMore && lastVisible >= total - 3) {
                    loadMore()
                }
            }
        })

        updatePlayModeUI()
    }

    private fun setPlayMode(mode: PlayMode) {
        PlayerManager.playMode = mode
        updatePlayModeUI()
    }

    private fun updatePlayModeUI() {
        val isSequential = PlayerManager.playMode == PlayMode.SEQUENTIAL
        binding.btnModeSequential.backgroundTintList =
            android.content.res.ColorStateList.valueOf(
                if (isSequential) android.graphics.Color.parseColor("#6C63FF")
                else android.graphics.Color.parseColor("#22223A")
            )
        binding.btnModeShuffle.backgroundTintList =
            android.content.res.ColorStateList.valueOf(
                if (!isSequential) android.graphics.Color.parseColor("#6C63FF")
                else android.graphics.Color.parseColor("#22223A")
            )
    }

    private fun setupRetrofit() {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
        youtubeApi = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(YouTubeApi::class.java)
    }

    private fun doSearch() {
        val query = binding.etSearch.text.toString().trim()
        if (query.isEmpty()) return
        currentQuery = query
        currentPage = 1
        currentTracks.clear()
        binding.btnSearch.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        fetchPage(1, reset = true)
    }

    private fun loadMore() {
        if (isLoadingMore || !hasMore) return
        isLoadingMore = true
        fetchPage(currentPage + 1, reset = false)
    }

    private fun fetchPage(page: Int, reset: Boolean) {
        val prefs = requireContext().getSharedPreferences("melodify_prefs", Context.MODE_PRIVATE)
        val searchCount = prefs.getInt("search_count", 20)

        youtubeApi.search(currentQuery, searchCount, page).enqueue(object : Callback<SearchResponse> {
            override fun onResponse(call: Call<SearchResponse>, response: Response<SearchResponse>) {
                binding.btnSearch.isEnabled = true
                binding.progressBar.visibility = View.GONE
                isLoadingMore = false

                if (response.isSuccessful) {
                    val body = response.body() ?: return
                    currentPage = body.page
                    hasMore = body.hasMore

                    val newTracks = body.tracks.map {
                        Track(it.videoId, it.title, it.author, it.thumbnails, "", it.duration)
                    }

                    if (reset) {
                        currentTracks = newTracks.toMutableList()
                        PlayerManager.currentQueue = currentTracks
                        trackAdapter = TrackAdapter(
                            currentTracks,
                            onTrackClick = { track ->
                                val index = currentTracks.indexOf(track)
                                PlayerManager.currentIndex = index
                                trackAdapter?.setPlayingPosition(index)
                                PlayerManager.urlResolver = { t, cb -> resolveAndPlay(t, cb) }
                                resolveAndPlay(track) { url -> onTrackSelected?.invoke(track, url) }
                            },
                            onDownloadClick = { track, position -> downloadAsMp3(track, position) },
                            onLongClick = { track -> showAddToPlaylistDialog(track) }
                        )
                        binding.rvTracks.adapter = trackAdapter
                        binding.playModeBar.visibility = View.VISIBLE
                    } else {
                        val startPos = currentTracks.size
                        currentTracks.addAll(newTracks)
                        PlayerManager.currentQueue = currentTracks
                        trackAdapter?.notifyItemRangeInserted(startPos, newTracks.size)
                    }

                    if (hasMore) {
                        Toast.makeText(requireContext(), "Daha fazlası için aşağı kaydır", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(requireContext(), "Hata: ${response.code()}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<SearchResponse>, t: Throwable) {
                binding.btnSearch.isEnabled = true
                binding.progressBar.visibility = View.GONE
                isLoadingMore = false
                Toast.makeText(requireContext(), "Bağlantı Hatası: ${t.message}", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun resolveAndPlay(track: Track, callback: ((String) -> Unit)? = null) {
        Toast.makeText(requireContext(), "Yükleniyor…", Toast.LENGTH_SHORT).show()
        youtubeApi.getVideoInfo(track.id).enqueue(object : Callback<InvidiousVideoInfo> {
            override fun onResponse(call: Call<InvidiousVideoInfo>, response: Response<InvidiousVideoInfo>) {
                val url = response.body()?.url
                if (url.isNullOrEmpty()) {
                    Toast.makeText(requireContext(), "Stream alınamadı", Toast.LENGTH_SHORT).show()
                    return
                }
                if (callback != null) callback(url)
                else onTrackSelected?.invoke(track, url)
            }
            override fun onFailure(call: Call<InvidiousVideoInfo>, t: Throwable) {
                Toast.makeText(requireContext(), "Hata: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun showAddToPlaylistDialog(track: Track) {
        val db = AppDatabase.getInstance(requireContext())
        lifecycleScope.launch {
            db.playlistDao().getAllPlaylists().collect { playlists ->
                if (playlists.isEmpty()) {
                    Toast.makeText(requireContext(), "Önce playlist oluştur", Toast.LENGTH_SHORT).show()
                    return@collect
                }
                val names = playlists.map { it.name }.toTypedArray()
                AlertDialog.Builder(requireContext())
                    .setTitle("Playlist'e Ekle")
                    .setItems(names) { _, index ->
                        val playlist = playlists[index]
                        lifecycleScope.launch {
                            val already = db.playlistSongDao().isSongInPlaylist(playlist.id, track.id)
                            if (already > 0) {
                                Toast.makeText(requireContext(), "Zaten listede", Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            db.playlistSongDao().insertSong(
                                PlaylistSongEntity(
                                    playlistId = playlist.id,
                                    videoId = track.id,
                                    title = track.name,
                                    author = track.artistName,
                                    thumbnail = track.image,
                                    duration = track.duration
                                )
                            )
                            Toast.makeText(requireContext(), "\"${playlist.name}\" listesine eklendi", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .show()
            }
        }
    }

    private fun downloadAsMp3(track: Track, position: Int) {
        // Zaten indirilmiş mi kontrol et
        val fileName = "${track.name.take(60).replace(Regex("[/\\\\:*?\"<>|]"), "_")}.mp3"
        val musicDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC)
        val existingFile = java.io.File(musicDir, fileName)
        if (existingFile.exists()) {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Zaten İndirilmiş")
                .setMessage("\"${track.name}\" zaten müzik klasöründe mevcut. Tekrar indirilsin mi?")
                .setPositiveButton("Evet") { _, _ -> startDownload(track, position, fileName) }
                .setNegativeButton("Hayır", null)
                .show()
            return
        }
        startDownload(track, position, fileName)
    }

    private fun startDownload(track: Track, position: Int, fileName: String) {
        trackAdapter?.registerPreparing(position)
        val downloadUrl = "${BASE_URL}download/${track.id}"
        try {
            val request = DownloadManager.Request(Uri.parse(downloadUrl))
                .setTitle(track.name)
                .setDescription("MP3 dönüştürülüyor…")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setMimeType("audio/mpeg")
            val dm = requireContext().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = dm.enqueue(request)
            activeDownloads.add(downloadId)
            startProgressPolling(dm, downloadId, position, fileName)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "İndirme hatası: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startProgressPolling(dm: DownloadManager, downloadId: Long, position: Int, fileName: String) {
        val poll = object : Runnable {
            override fun run() {
                if (!activeDownloads.contains(downloadId)) return
                val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))
                if (cursor != null && cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val down = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    when (status) {
                        DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED -> handler.postDelayed(this, 1000)
                        DownloadManager.STATUS_RUNNING -> {
                            if (total > 0) {
                                val percent = ((down * 100) / total).toInt()
                                trackAdapter?.registerDownload(downloadId, position)
                                trackAdapter?.updateProgress(downloadId, percent)
                            }
                            handler.postDelayed(this, 500)
                        }
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            activeDownloads.remove(downloadId)
                            trackAdapter?.registerDownload(downloadId, position)
                            trackAdapter?.markCompleted(downloadId)
                            
                            // Sistemi yeni dosyadan haberdar et
                            val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                            val file = File(musicDir, fileName)
                            if (file.exists()) {
                                MediaScannerConnection.scanFile(
                                    requireContext(),
                                    arrayOf(file.absolutePath),
                                    arrayOf("audio/mpeg"),
                                    null
                                )
                            }
                        }
                        DownloadManager.STATUS_FAILED -> {
                            activeDownloads.remove(downloadId)
                            Toast.makeText(requireContext(), "İndirme başarısız", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                cursor?.close()
            }
        }
        handler.post(poll)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
