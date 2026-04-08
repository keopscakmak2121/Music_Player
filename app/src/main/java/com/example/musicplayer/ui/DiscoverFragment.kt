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
import kotlinx.coroutines.delay
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
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DiscoverFragment : Fragment() {

    private var _binding: FragmentDiscoverBinding? = null
    private val binding get() = _binding!!
    private lateinit var youtubeApi: YouTubeApi
    private lateinit var downloadClient: OkHttpClient
    private var trackAdapter: TrackAdapter? = null
    private var currentTracks: MutableList<Track> = mutableListOf()

    // ConcurrentHashMap ile thread-safe hale getirildi
    private val activeDownloads = ConcurrentHashMap<String, DownloadInfo>()
    private val activeCallRef = ConcurrentHashMap<String, okhttp3.Call>()
    private val fakeIdToVideoId = ConcurrentHashMap<Long, String>()
    private var fakeIdCounter = 0L

    private var currentQuery = ""
    private var currentPage = 1
    private var isLoadingMore = false
    private var hasMore = false
    
    // Download cache için
    private val downloadedNamesCache = mutableSetOf<String>()
    private var lastCacheUpdate = 0L
    private val CACHE_DURATION_MS = 30000L // 30 saniye cache

    var onTrackSelected: ((Track, String) -> Unit)? = null

    private val playbackStateListener: (Boolean) -> Unit = { isPlaying ->
        trackAdapter?.notifyPlayingStateChanged()
    }

    companion object {
        private const val TAG = "MelodifySearch"
        const val BASE_URL = "http://100.122.252.85:5050/"
        private const val NOTIF_CHANNEL = "melodify_downloads"
        private const val DOWNLOAD_CHUNK_SIZE = 1024 * 1024 // 1MB chunk
        private const val MAX_RETRY_COUNT = 3
        private const val RETRY_DELAY_MS = 1000L
    }
    
    data class DownloadInfo(
        val job: Job,
        val cancelled: AtomicBoolean,
        var call: okhttp3.Call?,
        val resumeInfo: DownloadResumeInfo? = null
    )
    
    data class DownloadResumeInfo(
        val file: File,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val tempFile: File
    )

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
        
        // Sadece görünen item'ları kontrol et (performans optimizasyonu)
        val lm = binding.rvTracks.layoutManager as? LinearLayoutManager ?: return
        val firstVisible = lm.findFirstVisibleItemPosition()
        val lastVisible = lm.findLastVisibleItemPosition()
        
        if (firstVisible == -1) return
        
        val startIdx = maxOf(fromIndex, firstVisible)
        val endIdx = minOf(currentTracks.size - 1, lastVisible)
        
        if (startIdx > endIdx) return
        
        lifecycleScope.launch(Dispatchers.IO) {
            val downloadedNames = getDownloadedFileNamesCached()
            val updates = mutableListOf<Triple<Int, String, Boolean>>()

            for (i in startIdx..endIdx) {
                if (i >= currentTracks.size) break
                val track = currentTracks[i]
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

    // Cache'li dosya adı kontrolü
    private suspend fun getDownloadedFileNamesCached(): Set<String> {
        val now = System.currentTimeMillis()
        if (now - lastCacheUpdate > CACHE_DURATION_MS || downloadedNamesCache.isEmpty()) {
            val fresh = withContext(Dispatchers.IO) { getDownloadedFileNames() }
            withContext(Dispatchers.Main) {
                downloadedNamesCache.clear()
                downloadedNamesCache.addAll(fresh)
                lastCacheUpdate = now
            }
            return fresh
        }
        return downloadedNamesCache.toSet()
    }

    private fun syncPlaylistState() {
        if (!isAdded || trackAdapter == null) return
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getInstance(requireContext())
            val playlistMap = mutableMapOf<Int, Boolean>()
            currentTracks.forEachIndexed { index, track ->
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
            
            // Sadece Melodify klasörlerini tara (performans için)
            listOf(
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Melodify"),
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Melodify")
            ).forEach { dir ->
                if (dir.exists()) {
                    dir.listFiles()?.forEach { if (it.isFile) names.add(it.name) }
                }
            }
        } catch (e: Exception) { 
            Log.e(TAG, "getDownloadedFileNames error", e) 
        }
        return names
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

    private fun setupDownloadClient() {
        downloadClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)   // 0 = sınırsız: büyük dosyalarda chunk arası bekleme timeout'u olmasın
            .writeTimeout(0, TimeUnit.SECONDS)  // 0 = sınırsız
            .callTimeout(0, TimeUnit.SECONDS)   // 0 = sınırsız: toplam işlem süresi sınırsız
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
                        onTrackClick = { track ->
                            // Track'e tıklanınca çal
                            val position = currentTracks.indexOfFirst { it.id == track.id }
                            if (position != -1) {
                                val currentPlayingId = PlayerManager.currentQueue.getOrNull(PlayerManager.currentIndex)?.id
                                if (track.id == currentPlayingId) {
                                    PlayerManager.togglePlayPause()
                                } else {
                                    PlayerManager.playQueue(currentTracks.toList(), position)
                                }
                            }
                        },
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
                        onLongClick = { track -> 
                            trackAdapter?.toggleSelection(track.id)
                        },
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
                Toast.makeText(requireContext(), "Arama hatası: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun showDownloadOptions(track: Track, position: Int) {
        if (activeDownloads.containsKey(track.id)) return
        if (trackAdapter?.isDownloaded(position) == true) return
        
        // Devam eden indirme var mı kontrol et
        val tempFile = getTempFile(track)
        if (tempFile.exists() && tempFile.length() > 0) {
            AlertDialog.Builder(requireContext())
                .setTitle("Devam Eden İndirme")
                .setMessage("Bu şarkının yarım kalan indirmesi var. Devam etmek ister misiniz?")
                .setPositiveButton("Devam Et") { _, _ ->
                    resumeDownload(track, position)
                }
                .setNegativeButton("Yeniden Başlat") { _, _ ->
                    tempFile.delete()
                    showFormatDialog(track, position)
                }
                .setNeutralButton("İptal", null)
                .show()
        } else {
            showFormatDialog(track, position)
        }
    }
    
    private fun showFormatDialog(track: Track, position: Int) {
        val options = arrayOf("MP3 (Ses)", "MP4 (Video - 720p)", "MP4 (Video - 360p)")
        AlertDialog.Builder(requireContext())
            .setTitle("Format Seçin")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startDownloadWithResume(track, position, "mp3", null)
                    1 -> startDownloadWithResume(track, position, "mp4", "720")
                    2 -> startDownloadWithResume(track, position, "mp4", "360")
                }
            }
            .show()
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
                startDownloadWithResume(track, position, "mp3", null)
            }
        } else {
            startDownloadWithResume(track, -1, "mp3", null)
        }
    }

    private fun downloadSelected() {
        val prefs = requireContext().getSharedPreferences("melodify_prefs", Context.MODE_PRIVATE)
        val limit = prefs.getInt("download_limit", 3)
        val selectedTracks = trackAdapter?.getSelectedTracks() ?: return
        val toDownload = selectedTracks.filter { !activeDownloads.containsKey(it.id) && it.audio.isEmpty() }

        if (toDownload.isEmpty()) { trackAdapter?.exitSelectionMode(); return }

        AlertDialog.Builder(requireContext())
            .setTitle("Seçilenleri İndir")
            .setMessage("${toDownload.size} şarkı indirilsin mi?")
            .setPositiveButton("İndir") { _, _ ->
                var started = 0
                toDownload.forEach { track ->
                    if (started >= limit) return@forEach
                    val pos = currentTracks.indexOf(track)
                    if (pos >= 0) { 
                        startDownloadWithResume(track, pos, "mp3", null)
                        started++ 
                    }
                }
                trackAdapter?.exitSelectionMode()
            }
            .setNegativeButton("İptal", null)
            .show()
    }
    
    // Geçici dosya yolunu al
    private fun getTempFile(track: Track): File {
        val safeName = FileUtils.getSafeFileName(track.name)
        val tempDir = requireContext().cacheDir
        return File(tempDir, "${safeName}.tmp")
    }
    
    // Kısmi indirmeyi kontrol et ve devam et
    private suspend fun checkPartialDownload(track: Track, format: String, quality: String?): DownloadResumeInfo? {
        val tempFile = getTempFile(track)
        if (!tempFile.exists() || tempFile.length() == 0L) return null
        
        val url = buildDownloadUrl(track.id, format, quality)
        val request = Request.Builder()
            .url(url)
            .head()
            .build()
        
        return try {
            val response = downloadClient.newCall(request).execute()
            if (response.isSuccessful) {
                // DÜZELTİLDİ: headers() yerine headers property kullan
                val totalBytes = response.headers["Content-Length"]?.toLongOrNull() ?: 0L
                val downloadedBytes = tempFile.length()
                
                if (totalBytes > 0 && downloadedBytes < totalBytes && downloadedBytes > 0) {
                    DownloadResumeInfo(
                        file = File(requireContext().cacheDir, "temp_${track.id}"),
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes,
                        tempFile = tempFile
                    )
                } else {
                    tempFile.delete()
                    null
                }
            } else {
                tempFile.delete()
                null
            }
        } catch (e: Exception) {
            tempFile.delete()
            null
        }
    }
    
    private fun buildDownloadUrl(videoId: String, format: String, quality: String?): String {
        var url = "${BASE_URL}download/$videoId?format=$format"
        if (quality != null) url += "&quality=$quality"
        return url
    }
    
    private fun startDownloadWithResume(track: Track, position: Int, format: String, quality: String?) {
        if (activeDownloads.containsKey(track.id)) return
        
        lifecycleScope.launch(Dispatchers.IO) {
            // Kısmi indirme kontrolü
            val resumeInfo = checkPartialDownload(track, format, quality)
            if (resumeInfo != null && resumeInfo.downloadedBytes > 0) {
                withContext(Dispatchers.Main) {
                    startResumableDownload(track, position, format, quality, resumeInfo)
                }
            } else {
                withContext(Dispatchers.Main) {
                    startDownload(track, position, format, quality, null)
                }
            }
        }
    }
    
    private fun resumeDownload(track: Track, position: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            // Format ve quality'yi belirlemek için temp dosyasından veya kayıtlı bilgiden al
            // Basitlik için mp3 formatında devam et
            val tempFile = getTempFile(track)
            if (tempFile.exists() && tempFile.length() > 0) {
                val resumeInfo = DownloadResumeInfo(
                    file = File(requireContext().cacheDir, "temp_${track.id}"),
                    downloadedBytes = tempFile.length(),
                    totalBytes = tempFile.length() + 1, // Geçici, gerçek değer head request ile alınacak
                    tempFile = tempFile
                )
                withContext(Dispatchers.Main) {
                    startResumableDownload(track, position, "mp3", null, resumeInfo)
                }
            }
        }
    }
    
    private fun startResumableDownload(
        track: Track, 
        position: Int, 
        format: String, 
        quality: String?,
        resumeInfo: DownloadResumeInfo
    ) {
        val url = buildDownloadUrl(track.id, format, quality)
        val fakeId = ++fakeIdCounter
        fakeIdToVideoId[fakeId] = track.id
        val cancelled = AtomicBoolean(false)

        if (position >= 0) {
            trackAdapter?.registerPreparing(position)
            trackAdapter?.registerDownload(fakeId, position)
            val progress = ((resumeInfo.downloadedBytes * 100) / maxOf(resumeInfo.totalBytes, 1L)).toInt()
            trackAdapter?.updateProgress(fakeId, progress)
        }

        val notifId = track.id.hashCode()
        val nm = requireContext().getSystemService(NotificationManager::class.java)
        val ctx = requireContext().applicationContext

        val job = lifecycleScope.launch(Dispatchers.IO) {
            var retryCount = 0
            var success = false
            
            while (retryCount < MAX_RETRY_COUNT && !success && !cancelled.get()) {
                try {
                    success = performResumableDownload(track, position, format, quality, fakeId, 
                        resumeInfo, cancelled, notifId, nm, ctx, retryCount)
                    if (!success && !cancelled.get()) {
                        retryCount++
                        if (retryCount < MAX_RETRY_COUNT) {
                            delay(RETRY_DELAY_MS)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Download error: ${e.message}", e)
                    retryCount++
                    if (retryCount < MAX_RETRY_COUNT && !cancelled.get()) {
                        delay(RETRY_DELAY_MS)
                    }
                }
            }
            
            if (!success && !cancelled.get()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "İndirme başarısız: ${track.name}", Toast.LENGTH_LONG).show()
                    if (position >= 0) trackAdapter?.cancelDownload(position)
                }
            }
            
            activeDownloads.remove(track.id)
            fakeIdToVideoId.remove(fakeId)
            nm?.cancel(notifId)
        }
        
        activeDownloads[track.id] = DownloadInfo(job, cancelled, null, resumeInfo)
    }
    
    private suspend fun performResumableDownload(
        track: Track,
        position: Int,
        format: String,
        quality: String?,
        fakeId: Long,
        resumeInfo: DownloadResumeInfo,
        cancelled: AtomicBoolean,
        notifId: Int,
        nm: NotificationManager?,
        ctx: Context,
        retryCount: Int
    ): Boolean {
        val url = buildDownloadUrl(track.id, format, quality)
        val downloadedBytes = resumeInfo.downloadedBytes
        
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$downloadedBytes-")
            .build()
        
        val call = downloadClient.newCall(request)
        activeCallRef[track.id] = call
        
        val response = call.execute()
        
        if (!response.isSuccessful && response.code != 206) { // 206 = Partial Content
            return false
        }
        
        val body = response.body ?: return false
        val totalBytes = resumeInfo.totalBytes
        val tempFile = resumeInfo.tempFile
        
        val notifBuilder = NotificationCompat.Builder(ctx, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(track.name)
            .setProgress(100, ((downloadedBytes * 100) / totalBytes).toInt(), false)
        
        if (retryCount > 0) {
            notifBuilder.setContentText("Devam ediliyor... (Deneme ${retryCount + 1}/$MAX_RETRY_COUNT)")
        }
        
        nm?.notify(notifId, notifBuilder.build())
        
        var currentDownloaded = downloadedBytes
        var lastProgressUpdate = 0L
        
        RandomAccessFile(tempFile, "rw").use { raf ->
            raf.seek(downloadedBytes)
            body.byteStream().use { inputStream ->
                val buffer = ByteArray(DOWNLOAD_CHUNK_SIZE)
                var bytesRead: Int
                
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (cancelled.get()) {
                        return false
                    }
                    raf.write(buffer, 0, bytesRead)
                    currentDownloaded += bytesRead
                    
                    val now = System.currentTimeMillis()
                    if (now - lastProgressUpdate > 500) { // Progress update throttling
                        val progress = ((currentDownloaded * 100) / totalBytes).toInt()
                        withContext(Dispatchers.Main) {
                            if (position >= 0) {
                                trackAdapter?.updateProgress(fakeId, progress)
                            }
                        }
                        
                        notifBuilder.setProgress(100, progress, false)
                        nm?.notify(notifId, notifBuilder.build())
                        lastProgressUpdate = now
                    }
                }
            }
        }
        
        if (cancelled.get()) {
            return false
        }
        
        // İndirme tamamlandı, dosyayı kalıcı konuma taşı
        val finalFile = saveCompletedDownload(tempFile, track, format)
        
        if (finalFile != null) {
            withContext(Dispatchers.Main) {
                if (position >= 0) {
                    trackAdapter?.markCompleted(fakeId)
                    syncDownloadedState(position)
                } else {
                    Toast.makeText(ctx, "İndirme tamamlandı: ${track.name}", Toast.LENGTH_SHORT).show()
                }
            }
            return true
        }
        
        return false
    }
    
    private fun startDownload(track: Track, position: Int, format: String, quality: String?, resumeInfo: DownloadResumeInfo?) {
        val ext = if (format == "mp3") "mp3" else "mp4"
        val safeName = FileUtils.getSafeFileName(track.name)
        val fileName = "$safeName.$ext"
        val url = buildDownloadUrl(track.id, format, quality)

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
            var retryCount = 0
            var success = false
            
            while (retryCount < MAX_RETRY_COUNT && !success && !cancelled.get()) {
                try {
                    val req = Request.Builder().url(url).build()
                    val call = downloadClient.newCall(req)
                    activeCallRef[track.id] = call
                    val resp = call.execute()

                    if (cancelled.get() || !resp.isSuccessful) {
                        retryCount++
                        if (retryCount < MAX_RETRY_COUNT && !cancelled.get()) {
                            delay(RETRY_DELAY_MS)
                            continue
                        }
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

                    val notifBuilder = NotificationCompat.Builder(ctx, NOTIF_CHANNEL)
                        .setSmallIcon(android.R.drawable.stat_sys_download)
                        .setContentTitle(track.name)
                        .setProgress(100, 0, true)
                    
                    if (retryCount > 0) {
                        notifBuilder.setContentText("Yeniden deneniyor... (Deneme ${retryCount + 1}/$MAX_RETRY_COUNT)")
                    }
                    
                    nm?.notify(notifId, notifBuilder.build())

                    var downloaded = 0L
                    var lastProgressUpdate = 0L
                    
                    outputStream?.use { out ->
                        val buf = ByteArray(DOWNLOAD_CHUNK_SIZE)
                        body.byteStream().use { inp ->
                            while (true) {
                                if (cancelled.get()) break
                                val read = inp.read(buf)
                                if (read == -1) break
                                out.write(buf, 0, read)
                                downloaded += read
                                
                                val now = System.currentTimeMillis()
                                if (totalBytes > 0 && (now - lastProgressUpdate > 500)) {
                                    val pct = ((downloaded * 100) / totalBytes).toInt()
                                    withContext(Dispatchers.Main) { 
                                        if (position >= 0) trackAdapter?.updateProgress(fakeId, pct) 
                                    }
                                    
                                    notifBuilder.setProgress(100, pct, false)
                                    nm?.notify(notifId, notifBuilder.build())
                                    lastProgressUpdate = now
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
                        success = true
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Download error attempt ${retryCount + 1}: ${e.message}", e)
                    retryCount++
                    if (retryCount < MAX_RETRY_COUNT && !cancelled.get()) {
                        delay(RETRY_DELAY_MS)
                    }
                } finally {
                    if (!success && retryCount >= MAX_RETRY_COUNT && !cancelled.get()) {
                        withContext(Dispatchers.Main) { 
                            if (position >= 0) trackAdapter?.cancelDownload(position) 
                        }
                    }
                }
            }
            
            activeDownloads.remove(track.id)
            fakeIdToVideoId.remove(fakeId)
            activeCallRef.remove(track.id)
            nm?.cancel(notifId)
        }
        
        activeDownloads[track.id] = DownloadInfo(job, cancelled, null, resumeInfo)
    }
    
    private fun saveCompletedDownload(tempFile: File, track: Track, format: String): File? {
        val ctx = requireContext()
        val safeName = FileUtils.getSafeFileName(track.name)
        val ext = if (format == "mp3") "mp3" else "mp4"
        val fileName = "$safeName.$ext"
        
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, if (format == "mp3") "audio/mpeg" else "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, if (format == "mp3") "Music/Melodify" else "Download/Melodify")
                }
                val collection = if (format == "mp3") 
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else 
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                
                val uri = ctx.contentResolver.insert(collection, values)
                uri?.let {
                    ctx.contentResolver.openOutputStream(it)?.use { outputStream ->
                        tempFile.inputStream().use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    tempFile.delete()
                    uri.toString()
                }
                null
            } else {
                val dir = File(if (format == "mp3") 
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                else 
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Melodify")
                dir.mkdirs()
                val finalFile = File(dir, fileName)
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
                MediaScannerConnection.scanFile(ctx, arrayOf(finalFile.absolutePath), null, null)
                finalFile
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving completed download", e)
            null
        }
    }

    private fun cancelDownloadByFakeId(fakeId: Long) {
        val videoId = fakeIdToVideoId[fakeId] ?: return
        val downloadInfo = activeDownloads[videoId] ?: return
        downloadInfo.cancelled.set(true)
        activeCallRef[videoId]?.cancel()
        downloadInfo.job.cancel()
        
        // Temp dosyasını temizle
        val position = trackAdapter?.positionForFakeId(fakeId)
        if (position != null && position >= 0 && position < currentTracks.size) {
            val track = currentTracks[position]
            val tempFile = getTempFile(track)
            tempFile.delete()
        }
        
        fakeIdToVideoId.remove(fakeId)
        activeDownloads.remove(videoId)
        activeCallRef.remove(videoId)
        
        if (position != null) {
            trackAdapter?.cancelDownload(position)
        }
    }

    private fun resolveAndPlay(track: Track, callback: ((String) -> Unit)? = null) {
        val position = currentTracks.indexOfFirst { it.id == track.id }
        if (position >= 0) {
            trackAdapter?.registerLoading(position)
        }

        youtubeApi.getVideoInfo(track.id).enqueue(object : Callback<InvidiousVideoInfo> {
            override fun onResponse(call: Call<InvidiousVideoInfo>, response: Response<InvidiousVideoInfo>) {
                if (position >= 0) trackAdapter?.clearLoading(position)
                if (response.isSuccessful && response.body() != null) {
                    response.body()?.url?.let { callback?.invoke(it) }
                } else {
                    lifecycleScope.launch(Dispatchers.Main) {
                        Toast.makeText(context, "Video bilgisi alınamadı", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            
            override fun onFailure(call: Call<InvidiousVideoInfo>, t: Throwable) {
                if (position >= 0) trackAdapter?.clearLoading(position)
                lifecycleScope.launch(Dispatchers.Main) {
                    Toast.makeText(context, "Bağlantı hatası: ${t.message}", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    fun resetDownloadByPath(deletedFileName: String) {
        // Cache'leri temizle ve anlık güncelleme yap
        downloadedNamesCache.clear()
        lastCacheUpdate = 0L
        FileUtils.invalidateCache()
        
        // Görünen listedeki tüm eşleşen şarkıları güncelle
        currentTracks.forEachIndexed { index, track ->
            val safeName = FileUtils.getSafeFileName(track.name)
            // Silinen dosya adı uzantısız geliyor, safeName ile kısmi eşleşme kontrolü yap
            if (safeName.contains(deletedFileName, ignoreCase = true) || deletedFileName.contains(safeName, ignoreCase = true)) {
                trackAdapter?.cancelDownload(index)
                if (track.audio.isNotEmpty()) {
                    currentTracks[index] = track.copy(audio = "")
                }
            }
        }
        
        // Ekranda görünenleri tekrar doğrula (daha sağlam olması için)
        syncDownloadedState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        PlayerManager.removePlaybackStateListener(playbackStateListener)
        PlayerManager.urlResolver = null
        
        // Devam eden tüm indirmeleri iptal et
        activeDownloads.values.forEach { downloadInfo ->
            downloadInfo.cancelled.set(true)
            downloadInfo.call?.cancel()
            downloadInfo.job.cancel()
        }
        activeDownloads.clear()
        activeCallRef.clear()
        fakeIdToVideoId.clear()
        
        _binding = null
    }
}