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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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

    // videoId → (coroutine Job, cancelled flag, okhttp call)
    private val activeDownloads = mutableMapOf<String, Triple<Job, AtomicBoolean, okhttp3.Call?>>()
    private val activeCallRef = mutableMapOf<String, okhttp3.Call>()
    private val fakeIdToVideoId = mutableMapOf<Long, String>()
    private var fakeIdCounter = 0L

    private var currentQuery = ""
    private var currentPage = 1
    private var isLoadingMore = false
    private var hasMore = false

    // MainActivity tarafından dinlenen callback (Eksik olan kısım)
    var onTrackSelected: ((Track, String) -> Unit)? = null

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
        binding.btnDownloadAll.setOnClickListener { downloadAll("mp3") }
        binding.btnSelectAll.setOnClickListener { trackAdapter?.selectAll() }
        binding.btnCancelSelection.setOnClickListener { trackAdapter?.exitSelectionMode() }
        binding.btnDownloadSelected.setOnClickListener { downloadSelected() }

        // Sonsuz Kaydırma (Infinite Scroll)
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
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && currentTracks.isNotEmpty()) {
            syncDownloadedState()
        }
    }

    fun updatePlayingPosition(index: Int) {
        trackAdapter?.setPlayingPosition(index)
    }

    // ─── Lokal dosya yolu bulma ───────────────────────────────

    private fun findLocalUri(track: Track): String? {
        val ctx = context ?: return null
        val safeName = track.name.take(50).replace(Regex("[/\\\\:*?\"<>|]"), "_")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ctx.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Audio.Media.DISPLAY_NAME} = ?",
                arrayOf("Music/Melodify/%", "$safeName.mp3"), null
            )?.use { c ->
                if (c.moveToFirst()) return android.content.ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0)).toString()
            }
            ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf("Download/Melodify/%", "$safeName.mp4"), null
            )?.use { c ->
                if (c.moveToFirst()) return android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)).toString()
            }
        } else {
            val mp3 = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "$safeName.mp3")
            if (mp3.exists()) return mp3.absolutePath
            val mp4 = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "$safeName.mp4")
            if (mp4.exists()) return mp4.absolutePath
        }
        return null
    }

    // ─── Sync: indirme durumlarını MediaStore'dan oku ─────────

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
                
                val safeName = track.name.take(50).replace(Regex("[/\\\\:*?\"<>|]"), "_")
                val isDownloaded = downloadedNames.contains("$safeName.mp3") || downloadedNames.contains("$safeName.mp4")
                
                if (isDownloaded) {
                    val uri = findLocalUri(track)
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

    private fun getDownloadedFileNames(): Set<String> {
        val ctx = requireContext()
        val names = mutableSetOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Audio.Media.DISPLAY_NAME), "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?", arrayOf("Music/Melodify/%"), null)
                ?.use { c -> while (c.moveToNext()) c.getString(0)?.let { names.add(it) } }
            ctx.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads.DISPLAY_NAME), "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?", arrayOf("Download/Melodify/%"), null)
                ?.use { c -> while (c.moveToNext()) c.getString(0)?.let { names.add(it) } }
        } else {
            listOf(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)).forEach { dir ->
                dir.walkTopDown().filter { it.isFile && (it.extension == "mp3" || it.extension == "mp4") }.forEach { names.add(it.name) }
            }
        }
        return names
    }

    // ─── Retrofit & Client ──────────────────────────────────

    private fun setupRetrofit() {
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.HEADERS }
        val client = OkHttpClient.Builder().addInterceptor(logging).connectTimeout(30, TimeUnit.SECONDS).build()
        youtubeApi = Retrofit.Builder().baseUrl(BASE_URL).client(client).addConverterFactory(GsonConverterFactory.create()).build().create(YouTubeApi::class.java)
    }

    private fun setupDownloadClient() {
        downloadClient = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(10, TimeUnit.MINUTES).build()
    }

    private fun createNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(NOTIF_CHANNEL, "İndirmeler", NotificationManager.IMPORTANCE_LOW)
            requireContext().getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    // ─── Arama & Sayfalama ───────────────────────────────────

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
                    if (!reset) Toast.makeText(requireContext(), "Daha fazla yüklenemedi", Toast.LENGTH_SHORT).show()
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
                            val index = currentTracks.indexOf(track)
                            PlayerManager.urlResolver = { t, cb ->
                                if (t.audio.isNotEmpty()) cb(t.audio) else resolveAndPlay(t, cb)
                            }
                            PlayerManager.playQueue(currentTracks.toList(), index)
                        },
                        onDownloadClick = { track, pos -> showDownloadOptions(track, pos) },
                        onLongClick = { track -> showAddToPlaylistDialog(track) },
                        onCancelDownload = { fakeId -> cancelDownloadByFakeId(fakeId) },
                        onSelectionChanged = { count ->
                            if (!isAdded || _binding == null) return@TrackAdapter
                            if (count == -1) {
                                binding.selectionBar.visibility = View.GONE
                                binding.btnDownloadAll.visibility = if (currentTracks.isNotEmpty()) View.VISIBLE else View.GONE
                            } else {
                                binding.selectionBar.visibility = View.VISIBLE
                                binding.btnDownloadAll.visibility = View.GONE
                                binding.tvSelectionCount.text = "$count şarkı seçildi"
                            }
                        }
                    )
                    binding.rvTracks.adapter = trackAdapter
                    binding.btnDownloadAll.visibility = if (currentTracks.isNotEmpty()) View.VISIBLE else View.GONE
                    syncDownloadedState(0)
                } else {
                    if (newTracks.isNotEmpty()) {
                        val startPos = currentTracks.size
                        currentTracks.addAll(newTracks)
                        trackAdapter?.notifyItemRangeInserted(startPos, newTracks.size)
                        syncDownloadedState(startPos)
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
                Toast.makeText(requireContext(), "Bağlantı hatası", Toast.LENGTH_SHORT).show()
            }
        })
    }

    // ─── İndirme & Diğerleri ─────────────────────────────────

    fun resetDownloadByPath(deletedFileName: String) {
        val position = currentTracks.indexOfFirst { track ->
            val safeName = track.name.take(50).replace(Regex("[/\\\\:*?\"<>|]"), "_")
            safeName == deletedFileName
        }
        if (position >= 0) {
            trackAdapter?.cancelDownload(position)
        }
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

    fun downloadAll(format: String = "mp3") {
        val prefs = requireContext().getSharedPreferences("melodify_prefs", Context.MODE_PRIVATE)
        val limit = prefs.getInt("download_limit", 3)
        val notDownloaded = currentTracks.filter { !activeDownloads.containsKey(it.id) && it.audio.isEmpty() }
        
        if (notDownloaded.isEmpty()) return
        AlertDialog.Builder(requireContext()).setTitle("Toplu İndir").setMessage("${notDownloaded.size} şarkı indirilsin mi?")
            .setPositiveButton("İndir") { _, _ ->
                var started = 0
                notDownloaded.forEach { track ->
                    if (started >= limit) return@forEach
                    val pos = currentTracks.indexOf(track)
                    if (pos >= 0) { startDownload(track, pos, format, null); started++ }
                }
            }.setNegativeButton("İptal", null).show()
    }

    private fun startDownload(track: Track, position: Int, format: String, quality: String?) {
        val ext = if (format == "mp3") "mp3" else "mp4"
        val safeName = track.name.take(50).replace(Regex("[/\\\\:*?\"<>|]"), "_")
        val fileName = "$safeName.$ext"
        var url = "${BASE_URL}download/${track.id}?format=$format"
        if (quality != null) url += "&quality=$quality"

        val fakeId = ++fakeIdCounter
        fakeIdToVideoId[fakeId] = track.id
        val cancelled = AtomicBoolean(false)

        trackAdapter?.registerPreparing(position)
        trackAdapter?.registerDownload(fakeId, position)

        val notifId = track.id.hashCode()
        val nm = requireContext().getSystemService(NotificationManager::class.java)
        val ctx = requireContext().applicationContext

        val job = lifecycleScope.launch(Dispatchers.IO) {
            var mediaStoreUri: Uri? = null
            var outputStream: OutputStream? = null
            try {
                val req = Request.Builder().url(url).build()
                val call = downloadClient.newCall(req)
                activeCallRef[track.id] = call
                val resp = call.execute()

                if (cancelled.get() || !resp.isSuccessful) {
                    withContext(Dispatchers.Main) { trackAdapter?.cancelDownload(position) }
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
                    val dir = if (format == "mp3") Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                              else Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    dir.mkdirs()
                    outputStream = File(dir, fileName).outputStream()
                }

                val notifBuilder = NotificationCompat.Builder(ctx, NOTIF_CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(track.name).setProgress(100, 0, true)
                nm?.notify(notifId, notifBuilder.build())

                outputStream?.use { out ->
                    val buf = ByteArray(64 * 1024)
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
                                withContext(Dispatchers.Main) { trackAdapter?.updateProgress(fakeId, pct) }
                            }
                        }
                    }
                }

                if (!cancelled.get()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ctx.contentResolver.update(mediaStoreUri!!, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                    }
                    withContext(Dispatchers.Main) {
                        trackAdapter?.markCompleted(fakeId)
                        val localUri = findLocalUri(track)
                        if (localUri != null) {
                            val idx = currentTracks.indexOfFirst { it.id == track.id }
                            if (idx >= 0) currentTracks[idx] = currentTracks[idx].copy(audio = localUri)
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { trackAdapter?.cancelDownload(position) }
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
        youtubeApi.getVideoInfo(track.id).enqueue(object : Callback<InvidiousVideoInfo> {
            override fun onResponse(call: Call<InvidiousVideoInfo>, response: Response<InvidiousVideoInfo>) {
                response.body()?.url?.let { callback?.invoke(it) }
            }
            override fun onFailure(call: Call<InvidiousVideoInfo>, t: Throwable) {}
        })
    }

    private fun showAddToPlaylistDialog(track: Track) {
        lifecycleScope.launch {
            val playlists = AppDatabase.getInstance(requireContext()).playlistDao().getAllPlaylists().first()
            if (playlists.isEmpty()) return@launch
            val names = playlists.map { it.name }.toTypedArray()
            AlertDialog.Builder(requireContext()).setItems(names) { _, i ->
                lifecycleScope.launch {
                    AppDatabase.getInstance(requireContext()).playlistSongDao().insertSong(PlaylistSongEntity(playlistId = playlists[i].id, videoId = track.id, title = track.name, author = track.artistName, thumbnail = track.image, duration = track.duration))
                }
            }.show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
