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
import android.util.Log
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
import okhttp3.logging.HttpLoggingInterceptor
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
    private val activeDownloads = mutableMapOf<Long, Int>()

    private var currentQuery = ""
    private var currentPage = 1
    private var isLoadingMore = false
    private var hasMore = false

    companion object {
        private const val TAG = "MelodifySearch"
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
            PlayerManager.playQueue(currentTracks.toList(), 0)
        }

        binding.rvTracks.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return // Sadece aşağı kaydırmada tetikle
                
                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                val total = layoutManager.itemCount
                
                if (!isLoadingMore && hasMore && lastVisible >= total - 4 && total > 0) {
                    loadMore()
                }
            }
        })

        updatePlayModeUI()
    }

    // MainActivity'den çağrılan metot
    fun updatePlayingPosition(index: Int) {
        trackAdapter?.setPlayingPosition(index)
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
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.HEADERS }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
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
        Log.d(TAG, "Requesting page: ${currentPage + 1}")
        fetchPage(currentPage + 1, reset = false)
    }

    private fun fetchPage(page: Int, reset: Boolean) {
        val prefs = requireContext().getSharedPreferences("melodify_prefs", Context.MODE_PRIVATE)
        val searchCount = prefs.getInt("search_count", 20)

        youtubeApi.search(currentQuery, searchCount, page).enqueue(object : Callback<SearchResponse> {
            override fun onResponse(call: Call<SearchResponse>, response: Response<SearchResponse>) {
                if (!isAdded) return
                binding.btnSearch.isEnabled = true
                binding.progressBar.visibility = View.GONE
                isLoadingMore = false

                if (response.isSuccessful) {
                    val body = response.body() ?: return
                    currentPage = body.page
                    hasMore = body.hasMore
                    Log.d(TAG, "Loaded page: $currentPage, hasMore: $hasMore, tracks: ${body.tracks.size}")

                    val newTracks = body.tracks.map {
                        Track(it.videoId, it.title, it.author, it.thumbnails, "", it.duration)
                    }

                    if (reset) {
                        currentTracks = newTracks.toMutableList()
                        trackAdapter = TrackAdapter(
                            currentTracks,
                            onTrackClick = { track ->
                                val index = currentTracks.indexOf(track)
                                PlayerManager.urlResolver = { t, cb -> resolveAndPlay(t, cb) }
                                PlayerManager.playQueue(currentTracks.toList(), index)
                            },
                            onDownloadClick = { track, pos -> showDownloadOptions(track, pos) },
                            onLongClick = { track -> showAddToPlaylistDialog(track) }
                        )
                        binding.rvTracks.adapter = trackAdapter
                        binding.playModeBar.visibility = View.VISIBLE
                    } else if (newTracks.isNotEmpty()) {
                        val startPos = currentTracks.size
                        currentTracks.addAll(newTracks)
                        trackAdapter?.notifyItemRangeInserted(startPos, newTracks.size)
                    } else {
                        hasMore = false
                    }
                }
            }

            override fun onFailure(call: Call<SearchResponse>, t: Throwable) {
                if (!isAdded) return
                binding.btnSearch.isEnabled = true
                binding.progressBar.visibility = View.GONE
                isLoadingMore = false
                Log.e(TAG, "Search failure: ${t.message}")
            }
        })
    }

    private fun showDownloadOptions(track: Track, position: Int) {
        val prefs = requireContext().getSharedPreferences("melodify_prefs", Context.MODE_PRIVATE)
        val limit = prefs.getInt("download_limit", 3)
        if (activeDownloads.size >= limit) {
            Toast.makeText(requireContext(), "Lütfen bekleyen indirmelerin bitmesini bekleyin.", Toast.LENGTH_LONG).show()
            return
        }
        val options = arrayOf("MP3 (Ses)", "MP4 (Video - 720p)", "MP4 (Video - 360p)")
        AlertDialog.Builder(requireContext())
            .setTitle("Format Seçin")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> downloadFile(track, position, "mp3")
                    1 -> downloadFile(track, position, "mp4", "720")
                    2 -> downloadFile(track, position, "mp4", "360")
                }
            }
            .show()
    }

    private fun downloadFile(track: Track, position: Int, format: String, quality: String? = null) {
        val extension = if (format == "mp3") "mp3" else "mp4"
        val fileName = "${track.name.take(50).replace(Regex("[/\\\\:*?\"<>|]"), "_")}.$extension"
        val dir = if (format == "mp3") Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
        startDownload(track, position, fileName, format, quality, dir)
    }

    private fun startDownload(track: Track, position: Int, fileName: String, format: String, quality: String?, dirType: String) {
        trackAdapter?.registerPreparing(position)
        var downloadUrl = "${BASE_URL}download/${track.id}?format=$format"
        if (quality != null) downloadUrl += "&quality=$quality"

        try {
            val request = DownloadManager.Request(Uri.parse(downloadUrl))
                .setTitle(track.name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(dirType, fileName)
            
            val dm = requireContext().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = dm.enqueue(request)
            activeDownloads[downloadId] = position
            trackAdapter?.registerDownload(downloadId, position)
            startProgressPolling(dm, downloadId, position, fileName, dirType)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Hata: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startProgressPolling(dm: DownloadManager, downloadId: Long, position: Int, fileName: String, dirType: String) {
        val poll = object : Runnable {
            override fun run() {
                if (!activeDownloads.containsKey(downloadId)) return
                val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))
                if (cursor != null && cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val down = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    when (status) {
                        DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED -> handler.postDelayed(this, 1000)
                        DownloadManager.STATUS_RUNNING -> {
                            if (total > 0) trackAdapter?.updateProgress(downloadId, ((down * 100) / total).toInt())
                            handler.postDelayed(this, 800)
                        }
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            activeDownloads.remove(downloadId)
                            trackAdapter?.markCompleted(downloadId)
                            MediaScannerConnection.scanFile(requireContext(), arrayOf(File(Environment.getExternalStoragePublicDirectory(dirType), fileName).absolutePath), null, null)
                        }
                        DownloadManager.STATUS_FAILED -> {
                            activeDownloads.remove(downloadId)
                            trackAdapter?.updateProgress(downloadId, 0)
                        }
                    }
                }
                cursor?.close()
            }
        }
        handler.post(poll)
    }

    private fun resolveAndPlay(track: Track, callback: ((String) -> Unit)? = null) {
        youtubeApi.getVideoInfo(track.id).enqueue(object : Callback<InvidiousVideoInfo> {
            override fun onResponse(call: Call<InvidiousVideoInfo>, response: Response<InvidiousVideoInfo>) {
                val url = response.body()?.url ?: return
                callback?.invoke(url)
            }
            override fun onFailure(call: Call<InvidiousVideoInfo>, t: Throwable) {}
        })
    }

    private fun showAddToPlaylistDialog(track: Track) {
        lifecycleScope.launch {
            AppDatabase.getInstance(requireContext()).playlistDao().getAllPlaylists().collect { playlists ->
                if (playlists.isEmpty()) return@collect
                val names = playlists.map { it.name }.toTypedArray()
                AlertDialog.Builder(requireContext()).setItems(names) { _, i ->
                    lifecycleScope.launch {
                        AppDatabase.getInstance(requireContext()).playlistSongDao().insertSong(
                            PlaylistSongEntity(playlistId = playlists[i].id, videoId = track.id, title = track.name, author = track.artistName, thumbnail = track.image, duration = track.duration)
                        )
                    }
                }.show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
