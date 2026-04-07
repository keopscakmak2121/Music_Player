package com.example.musicplayer.ui

import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.asLiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.musicplayer.PlayerManager
import com.example.musicplayer.TrackAdapter
import com.example.musicplayer.api.InvidiousVideoInfo
import com.example.musicplayer.api.SearchResponse
import com.example.musicplayer.api.YouTubeApi
import com.example.musicplayer.databinding.FragmentDiscoverBinding
import com.example.musicplayer.db.AppDatabase
import com.example.musicplayer.db.PlaylistSongEntity
import com.example.musicplayer.model.Track
import com.example.musicplayer.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DiscoverFragment : Fragment() {

    private var _binding: FragmentDiscoverBinding? = null
    private val binding get() = _binding!!
    private lateinit var youtubeApi: YouTubeApi
    private lateinit var downloadClient: OkHttpClient
    private var trackAdapter: TrackAdapter? = null
    private var currentTracks: MutableList<Track> = mutableListOf()

    private val activeDownloads = mutableMapOf<String, Triple<Job, AtomicBoolean, okhttp3.Call?>>()
    private val activeCallRef = mutableMapOf<String, okhttp3.Call>()
    private val fakeIdToVideoId = mutableMapOf<Long, String>()
    private var fakeIdCounter = 0L

    private var currentQuery = ""
    private var currentPage = 1
    private var isLoadingMore = false
    private var hasMore = false

    var onTrackSelected: ((Track, String) -> Unit)? = null

    private val playbackStateListener: (Boolean) -> Unit = { isPlaying ->
        trackAdapter?.notifyPlayingStateChanged()
    }

    companion object {
        private const val TAG = "MelodifySearch"
        const val BASE_URL = "http://77.92.154.224:5050/"
        private const val NOTIF_CHANNEL = "melodify_downloads"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDiscoverBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRetrofit()
        setupDownloadClient()
        createNotifChannel()

        binding.rvTracks.layoutManager = LinearLayoutManager(requireContext())
        binding.btnSearch.setOnClickListener { doSearch() }
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false
        }
        binding.btnSelectAll.setOnClickListener { trackAdapter?.selectAll() }
        binding.btnCancelSelection.setOnClickListener { trackAdapter?.exitSelectionMode() }
        binding.btnDownloadSelected.setOnClickListener { downloadSelected() }

        // YouTube Playlist import butonu
        binding.btnImportPlaylist.setOnClickListener {
            (activity as? com.example.musicplayer.MainActivity)?.openPlaylistImport()
        }

        binding.rvTracks.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0) {
                    val lm = recyclerView.layoutManager as LinearLayoutManager
                    val lastVisible = lm.findLastVisibleItemPosition()
                    val totalItems = lm.itemCount
                    if (!isLoadingMore && hasMore && lastVisible >= totalItems - 4) {
                        loadMore()
                    }
                }
            }
        })

        // PlayerManager'a dinamik URL çözücü ata
        PlayerManager.urlResolver = { track, callback ->
            val localUri = FileUtils.findLocalUri(requireContext(), track.name)
            if (localUri != null) {
                callback(localUri)
            } else {
                resolveAndPlay(track) { url ->
                    callback(url)
                }
            }
        }

        PlayerManager.addPlaybackStateListener(playbackStateListener)
    }

    override fun onResume() {
        super.onResume()
        if (currentTracks.isNotEmpty()) {
            syncDownloadedState()
            syncPlaylistState()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && currentTracks.isNotEmpty()) {
            syncDownloadedState()
            syncPlaylistState()
        }
    }

    fun updatePlayingPosition(index: Int) {
        trackAdapter?.setPlayingPosition(index)
    }

    private fun syncDownloadedState(fromIndex: Int = 0) {
        if (!isAdded || _binding == null) return
        val tracksToSync = currentTracks.toList()
        lifecycleScope.launch(Dispatchers.IO) {
            val downloadedNames = getDownloadedFileNames()
            val updates = mutableListOf<Triple<Int, String, Boolean>>()
            val checkRange = fromIndex until tracksToSync.size

            for (i in checkRange) {
                if (i >= tracksToSync.size) break
                val track = tracksToSync[i]
                if (activeDownloads.containsKey(track.id)) continue
                
                val safeName = FileUtils.getSafeFileName(track.name)
                val isDownloaded = downloadedNames.any { it.contains(safeName, true) }
                
                if (isDownloaded) {
                    val uri = FileUtils.findLocalUri(requireContext(), track.name)
                    if (uri != null) updates.add(Triple(i, uri, true))
                    else updates.add(Triple(i, "", false))
                } else {
                    updates.add(Triple(i, "", false))
                }
            }

            withContext(Dispatchers.Main) {
                if (!isAdded || _binding == null) return@withContext
                updates.forEach { (index, localUri, isDownloaded) ->
                    if (index >= currentTracks.size) return@forEach
                    val track = currentTracks[index]
                    val currentState = trackAdapter?.isDownloaded(index) ?: false

                    if (isDownloaded) {
                        if (track.audio != localUri) currentTracks[index] = track.copy(audio = localUri)
                        if (!currentState) trackAdapter?.markCompletedByPosition(index)
                    } else {
                        if (track.audio.isNotEmpty()) currentTracks[index] = track.copy(audio = "")
                        if (currentState) trackAdapter?.cancelDownload(index)
                    }
                }
            }
        }
    }

    private fun syncPlaylistState() {
        if (!isAdded || trackAdapter == null) return
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getInstance(requireContext())
            val playlistMap = mutableMapOf<Int, Boolean>()
            currentTracks.forEachIndexed { index, track ->
                // Herhangi bir listede var mı?
                val count = db.playlistSongDao().isSongInAnyPlaylist(track.id)
                if (count > 0) playlistMap[index] = true
            }
            withContext(Dispatchers.Main) {
                trackAdapter?.setPlaylistMap(playlistMap)
            }
        }
    }

    private fun getDownloadedFileNames(): Set<String> {
        val ctx = context ?: return emptySet()
        val names = mutableSetOf<String>()
        try {
            val collections = mutableListOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                collections.add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                collections.add(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
            }
            collections.forEach { uri ->
                ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    val nameIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    while (c.moveToNext()) {
                        c.getString(nameIdx)?.let { names.add(it) }
                    }
                }
            }
            listOf(
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Melodify"),
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Melodify")
            ).forEach { dir ->
                if (dir.exists()) {
                    dir.listFiles()?.forEach { if (it.isFile) names.add(it.name) }
                }
            }
        } catch (e: Exception) { Log.e(TAG, "getDownloadedFileNames error", e) }
        return names
    }

    private fun setupRetrofit() {
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.HEADERS }
        val client = OkHttpClient.Builder().addInterceptor(logging).connectTimeout(30, TimeUnit.SECONDS).build()
        youtubeApi = Retrofit.Builder().baseUrl(BASE_URL).client(client).addConverterFactory(GsonConverterFactory.create()).build().create(YouTubeApi::class.java)
    }

    private fun setupDownloadClient() {
        downloadClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(10, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .build()
    }

    private fun createNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(NOTIF_CHANNEL, "İndirmeler", NotificationManager.IMPORTANCE_LOW)
            requireContext().getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun doSearch() {
        val query = binding.etSearch.text.toString().trim()
        if (query.isEmpty()) return
        currentQuery = query
        currentPage = 1
        hasMore = false
        isLoadingMore = false
        currentTracks.clear()
        binding.btnSearch.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        fetchPage(1, reset = true)
    }

    private fun loadMore() {
        if (isLoadingMore || !hasMore) return
        isLoadingMore = true
        trackAdapter?.setLoadingMore(true)
        fetchPage(currentPage + 1, reset = false)
    }

    private fun fetchPage(page: Int, reset: Boolean) {
        val prefs = requireContext().getSharedPreferences("melodify_prefs", Context.MODE_PRIVATE)
        val searchCount = prefs.getInt("search_count", 20)

        youtubeApi.search(currentQuery, searchCount, page).enqueue(object : Callback<SearchResponse> {
            override fun onResponse(call: Call<SearchResponse>, response: Response<SearchResponse>) {
                if (!isAdded || _binding == null) return
                binding.btnSearch.isEnabled = true
                binding.progressBar.visibility = View.GONE
                isLoadingMore = false
                trackAdapter?.setLoadingMore(false)

                if (!response.isSuccessful) {
                    hasMore = false
                    return
                }

                val body = response.body() ?: run { hasMore = false; return }
                currentPage = body.page
                hasMore = body.hasMore || (body.tracks.size >= searchCount && body.tracks.isNotEmpty())
                
                val newTracks = body.tracks.map {
                    Track(it.videoId, it.title, it.author, it.thumbnails, "", it.duration)
                }

                if (reset) {
                    currentTracks = newTracks.toMutableList()
                    trackAdapter = TrackAdapter(
                        currentTracks,
                        onTrackClick = { },
                        onDownloadClick = { track, pos -> showDownloadOptions(track, pos) },
                        onPlayClick = { track, pos ->
                            val currentPlayingId = PlayerManager.currentQueue.getOrNull(PlayerManager.currentIndex)?.id
                            if (track.id == currentPlayingId) {
                                PlayerManager.togglePlayPause()
                            } else {
                                PlayerManager.playQueue(currentTracks.toList(), pos)
                            }
                        },
                        onAddToPlaylistClick = { track -> showAddToPlaylistDialog(track) },
                        onLongClick = { track -> },
                        onCancelDownload = { fakeId -> cancelDownloadByFakeId(fakeId) },
                        onSelectionChanged = { count ->
                            if (!isAdded || _binding == null) return@TrackAdapter
                            if (count == -1) {
                                binding.selectionBar.visibility = View.GONE
                            } else {
                                binding.selectionBar.visibility = View.VISIBLE
                                binding.tvSelectionCount.text = "$count şarkı seçildi"
                            }
                        }
                    )
                    binding.rvTracks.adapter = trackAdapter
                    syncDownloadedState(0)
                    syncPlaylistState()
                } else {
                    if (newTracks.isNotEmpty()) {
                        val startPos = currentTracks.size
                        currentTracks.addAll(newTracks)
                        trackAdapter?.notifyItemRangeInserted(startPos, newTracks.size)
                        syncDownloadedState(startPos)
                        syncPlaylistState()
                    } else {
                        hasMore = false
                    }
                }
            }

            override fun onFailure(call: Call<SearchResponse>, t: Throwable) {
                if (!isAdded || _binding == null) return
                binding.btnSearch.isEnabled = true
                binding.progressBar.visibility = View.GONE
                isLoadingMore = false
                trackAdapter?.setLoadingMore(false)
            }
        })
    }

    private fun showDownloadOptions(track: Track, position: Int) {
        if (activeDownloads.containsKey(track.id)) return
        if (trackAdapter?.isDownloaded(position) == true) return
        val options = arrayOf("MP3 (Ses)", "MP4 (Video - 720p)", "MP4 (Video - 360p)")
        AlertDialog.Builder(requireContext()).setTitle("Format Seçin").setItems(options) { _, which ->
            when (which) {
                0 -> startDownload(track, position, "mp3", null)
                1 -> startDownload(track, position, "mp4", "720")
                2 -> startDownload(track, position, "mp4", "360")
            }
        }.show()
    }

    private fun showAddToPlaylistDialog(track: Track) {
        val db = AppDatabase.getInstance(requireContext())
        db.playlistDao().getAllPlaylists().asLiveData().observe(viewLifecycleOwner) { playlists ->
            if (playlists.isEmpty()) {
                Toast.makeText(context, "Henüz liste oluşturmadınız.", Toast.LENGTH_SHORT).show()
                return@observe
            }
            val names = playlists.map { it.name }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle("Listeye Ekle")
                .setItems(names) { _, which ->
                    val selectedPlaylist = playlists[which]
                    lifecycleScope.launch(Dispatchers.IO) {
                        val exists = db.playlistSongDao().isSongInPlaylist(selectedPlaylist.id, track.id)
                        if (exists > 0) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Bu şarkı zaten listede var.", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            db.playlistSongDao().insertSong(
                                PlaylistSongEntity(
                                    playlistId = selectedPlaylist.id,
                                    videoId = track.id,
                                    title = track.name,
                                    author = track.artistName,
                                    thumbnail = track.image,
                                    duration = track.duration
                                )
                            )
                            withContext(Dispatchers.Main) {
                                val position = currentTracks.indexOfFirst { it.id == track.id }
                                if (position >= 0) trackAdapter?.markInPlaylist(position)
                                
                                Toast.makeText(context, "Listeye eklendi ve indiriliyor.", Toast.LENGTH_SHORT).show()
                                triggerDownload(track)
                            }
                        }
                    }
                }
                .show()
        }
    }

    fun triggerDownload(track: Track) {
        val position = currentTracks.indexOfFirst { it.id == track.id }
        if (position >= 0) {
            if (!trackAdapter!!.isDownloaded(position) && !activeDownloads.containsKey(track.id)) {
                startDownload(track, position, "mp3", null)
            }
        } else {
            startDownload(track, -1, "mp3", null)
        }
    }

    private fun downloadSelected() {
        val prefs = requireContext().getSharedPreferences("melodify_prefs", Context.MODE_PRIVATE)
        val limit = prefs.getInt("download_limit", 3)
        val selectedTracks = trackAdapter?.getSelectedTracks() ?: return
        val toDownload = selectedTracks.filter { !activeDownloads.containsKey(it.id) && it.audio.isEmpty() }

        if (toDownload.isEmpty()) { trackAdapter?.exitSelectionMode(); return }

        AlertDialog.Builder(requireContext()).setTitle("Seçilenleri İndir").setMessage("${toDownload.size} şarkı indirilsin mi?")
            .setPositiveButton("İndir") { _, _ ->
                var started = 0
                toDownload.forEach { track ->
                    if (started >= limit) return@forEach
                    val pos = currentTracks.indexOf(track)
                    if (pos >= 0) { startDownload(track, pos, "mp3", null); started++ }
                }
                trackAdapter?.exitSelectionMode()
            }.setNegativeButton("İptal", null).show()
    }

    private fun startDownload(track: Track, position: Int, format: String, quality: String?) {
        val ext = if (format == "mp3") "mp3" else "mp4"
        val safeName = FileUtils.getSafeFileName(track.name)
        val fileName = "$safeName.$ext"
        var url = "${BASE_URL}download/${track.id}?format=$format"
        if (quality != null) url += "&quality=$quality"

        val fakeId = ++fakeIdCounter
        fakeIdToVideoId[fakeId] = track.id
        val cancelled = AtomicBoolean(false)

        if (position >= 0) {
            trackAdapter?.registerPreparing(position)
            trackAdapter?.registerDownload(fakeId, position)
        }

        val notifId = track.id.hashCode()
        val nm = requireContext().getSystemService(NotificationManager::class.java)
        val ctx = requireContext().applicationContext

        val job = lifecycleScope.launch(Dispatchers.IO) {
            var mediaStoreUri: Uri? = null
            var outputStream: OutputStream? = null
            var finalFile: File? = null
            try {
                val req = Request.Builder().url(url).build()
                val call = downloadClient.newCall(req)
                activeCallRef[track.id] = call
                val resp = call.execute()

                if (cancelled.get() || !resp.isSuccessful) {
                    withContext(Dispatchers.Main) { if (position >= 0) trackAdapter?.cancelDownload(position) }
                    return@launch
                }

                val body = resp.body ?: return@launch
                val totalBytes = body.contentLength()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, if (format == "mp3") "audio/mpeg" else "video/mp4")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, if (format == "mp3") "Music/Melodify" else "Download/Melodify")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val collection = if (format == "mp3") MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                                     else MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    mediaStoreUri = ctx.contentResolver.insert(collection, values)
                    outputStream = ctx.contentResolver.openOutputStream(mediaStoreUri!!)
                } else {
                    val dir = File(if (format == "mp3") Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                              else Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Melodify")
                    dir.mkdirs()
                    finalFile = File(dir, fileName)
                    outputStream = finalFile.outputStream()
                }

                val notifBuilder = NotificationCompat.Builder(ctx, NOTIF_CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(track.name).setProgress(100, 0, true)
                nm?.notify(notifId, notifBuilder.build())

                outputStream?.use { out ->
                    val buf = ByteArray(128 * 1024)
                    var downloaded = 0L
                    body.byteStream().use { inp ->
                        while (true) {
                            if (cancelled.get()) break
                            val read = inp.read(buf)
                            if (read == -1) break
                            out.write(buf, 0, read)
                            downloaded += read
                            
                            if (totalBytes > 0) {
                                val pct = ((downloaded * 100) / totalBytes).toInt()
                                withContext(Dispatchers.Main) { 
                                    if (position >= 0) trackAdapter?.updateProgress(fakeId, pct) 
                                }
                            }
                        }
                    }
                }

                if (!cancelled.get()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ctx.contentResolver.update(mediaStoreUri!!, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                    } else {
                        MediaScannerConnection.scanFile(ctx, arrayOf(finalFile?.absolutePath), null, null)
                    }
                    
                    withContext(Dispatchers.Main) {
                        if (position >= 0) {
                            trackAdapter?.markCompleted(fakeId)
                            syncDownloadedState(position)
                        } else {
                            Toast.makeText(ctx, "İndirme tamamlandı: ${track.name}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { if (position >= 0) trackAdapter?.cancelDownload(position) }
            } finally {
                activeDownloads.remove(track.id)
                nm?.cancel(notifId)
            }
        }
        activeDownloads[track.id] = Triple(job, cancelled, null)
    }

    private fun cancelDownloadByFakeId(fakeId: Long) {
        val videoId = fakeIdToVideoId[fakeId] ?: return
        val triple = activeDownloads[videoId] ?: return
        triple.second.set(true)
        activeCallRef[videoId]?.cancel()
        triple.first.cancel()
        val position = trackAdapter?.positionForFakeId(fakeId) ?: return
        trackAdapter?.cancelDownload(position)
    }

    private fun resolveAndPlay(track: Track, callback: ((String) -> Unit)? = null) {
        val position = currentTracks.indexOfFirst { it.id == track.id }
        if (position >= 0) {
            trackAdapter?.registerLoading(position)
        }

        youtubeApi.getVideoInfo(track.id).enqueue(object : Callback<InvidiousVideoInfo> {
            override fun onResponse(call: Call<InvidiousVideoInfo>, response: Response<InvidiousVideoInfo>) {
                if (position >= 0) trackAdapter?.clearLoading(position)
                response.body()?.url?.let { callback?.invoke(it) }
            }
            override fun onFailure(call: Call<InvidiousVideoInfo>, t: Throwable) {
                if (position >= 0) trackAdapter?.clearLoading(position)
                Toast.makeText(context, "Bağlantı hatası", Toast.LENGTH_SHORT).show()
            }
        })
    }

    fun resetDownloadByPath(deletedFileName: String) {
        val position = currentTracks.indexOfFirst { track ->
            val safeName = FileUtils.getSafeFileName(track.name)
            safeName == deletedFileName
        }
        if (position >= 0) {
            trackAdapter?.cancelDownload(position)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        PlayerManager.removePlaybackStateListener(playbackStateListener)
        PlayerManager.urlResolver = null  // Memory leak fix: singleton'daki fragment referansını temizle
        _binding = null
    }
}
