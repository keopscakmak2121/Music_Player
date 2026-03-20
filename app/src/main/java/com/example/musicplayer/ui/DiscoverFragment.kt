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
import com.example.musicplayer.PlayMode
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

    // videoId → (coroutine Job, cancelled flag)
    private val activeDownloads = mutableMapOf<String, Pair<Job, AtomicBoolean>>()
    // fakeId → videoId
    private val fakeIdToVideoId = mutableMapOf<Long, String>()
    private var fakeIdCounter = 0L

    private var currentQuery = ""
    private var currentPage = 1
    private var isLoadingMore = false
    private var hasMore = false

    companion object {
        private const val TAG = "MelodifySearch"
        const val BASE_URL = "http://77.92.154.224:5050/"
        private const val NOTIF_CHANNEL = "melodify_downloads"
    }

    var onTrackSelected: ((Track, String) -> Unit)? = null

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
        binding.btnModeSequential.setOnClickListener { setPlayMode(PlayMode.SEQUENTIAL) }
        binding.btnModeShuffle.setOnClickListener { setPlayMode(PlayMode.SHUFFLE) }
        binding.btnPlayAll.setOnClickListener {
            if (currentTracks.isEmpty()) return@setOnClickListener
            PlayerManager.urlResolver = { track, cb -> resolveAndPlay(track, cb) }
            PlayerManager.playQueue(currentTracks.toList(), 0)
        }

        binding.rvTracks.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = recyclerView.layoutManager as LinearLayoutManager
                val last = lm.findLastVisibleItemPosition()
                val total = lm.itemCount
                if (!isLoadingMore && hasMore && last >= total - 4 && total > 0) loadMore()
            }
        })

        updatePlayModeUI()
    }

    fun updatePlayingPosition(index: Int) {
        trackAdapter?.setPlayingPosition(index)
    }

    /**
     * MediaStore'daki Melodify dosyalarını okuyup adapter'ı senkronize eder.
     * Uygulama açılınca veya yeni arama yapılınca çağrılır.
     */
    private fun syncDownloadedState() {
        lifecycleScope.launch(Dispatchers.IO) {
            val downloadedNames = getDownloadedFileNames()
            withContext(Dispatchers.Main) {
                currentTracks.forEachIndexed { index, track ->
                    val safeName = track.name.take(50).replace(Regex("[/\\\\:*?\"<>|]"), "_")
                    if (downloadedNames.contains("$safeName.mp3") ||
                        downloadedNames.contains("$safeName.mp4")) {
                        trackAdapter?.markCompletedByPosition(index)
                    }
                }
            }
        }
    }

    /**
     * İndirilenler sayfasından dosya silinince çağrılır.
     * Dosya adı (uzantısız) ile track'i bulup adapter durumunu sıfırlar.
     */
    fun resetDownloadByPath(deletedFileName: String) {
        val position = currentTracks.indexOfFirst { track ->
            val safeName = track.name.take(50).replace(Regex("[/\\\\:*?\"<>|]"), "_")
            safeName == deletedFileName
        }
        if (position >= 0) {
            trackAdapter?.cancelDownload(position)
        }
    }

    /** MediaStore'daki Music/Melodify ve Download/Melodify dosya adlarını döner */
    private fun getDownloadedFileNames(): Set<String> {
        val ctx = requireContext()
        val names = mutableSetOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // MP3
            ctx.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media.DISPLAY_NAME),
                "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("Music/Melodify/%"), null
            )?.use { c ->
                while (c.moveToNext())
                    c.getString(0)?.let { names.add(it) }
            }
            // MP4
            ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads.DISPLAY_NAME),
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf("Download/Melodify/%"), null
            )?.use { c ->
                while (c.moveToNext())
                    c.getString(0)?.let { names.add(it) }
            }
        } else {
            listOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ).forEach { dir ->
                dir.walkTopDown()
                    .filter { it.isFile && (it.extension == "mp3" || it.extension == "mp4") }
                    .forEach { names.add(it.name) }
            }
        }
        return names
    }

    private fun setPlayMode(mode: PlayMode) {
        PlayerManager.playMode = mode
        updatePlayModeUI()
    }

    private fun updatePlayModeUI() {
        val isSeq = PlayerManager.playMode == PlayMode.SEQUENTIAL
        binding.btnModeSequential.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (isSeq) android.graphics.Color.parseColor("#6C63FF") else android.graphics.Color.parseColor("#22223A")
        )
        binding.btnModeShuffle.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (!isSeq) android.graphics.Color.parseColor("#6C63FF") else android.graphics.Color.parseColor("#22223A")
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

    private fun setupDownloadClient() {
        downloadClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(10, TimeUnit.MINUTES)
            .build()
    }

    private fun createNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(NOTIF_CHANNEL, "İndirmeler", NotificationManager.IMPORTANCE_LOW)
            requireContext().getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    // ─── Arama ───────────────────────────────────────────────

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
                if (!isAdded) return
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
                        trackAdapter = TrackAdapter(
                            currentTracks,
                            onTrackClick = { track ->
                                val index = currentTracks.indexOf(track)
                                PlayerManager.urlResolver = { t, cb -> resolveAndPlay(t, cb) }
                                PlayerManager.playQueue(currentTracks.toList(), index)
                            },
                            onDownloadClick = { track, pos -> showDownloadOptions(track, pos) },
                            onLongClick = { track -> showAddToPlaylistDialog(track) },
                            onCancelDownload = { fakeId -> cancelDownloadByFakeId(fakeId) }
                        )
                        binding.rvTracks.adapter = trackAdapter
                        binding.playModeBar.visibility = View.VISIBLE
                        // MediaStore'daki indirilen dosyalarla senkronize et
                        syncDownloadedState()
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

    // ─── İndirme ─────────────────────────────────────────────

    private fun showDownloadOptions(track: Track, position: Int) {
        // Zaten indiriliyor mu?
        if (activeDownloads.containsKey(track.id)) {
            Toast.makeText(requireContext(), "Bu şarkı zaten indiriliyor", Toast.LENGTH_SHORT).show()
            return
        }
        // Zaten indirildi mi? (adapter state)
        if (trackAdapter?.isDownloaded(position) == true) {
            Toast.makeText(requireContext(), "Bu şarkı zaten indirildi", Toast.LENGTH_SHORT).show()
            return
        }
        val options = arrayOf("MP3 (Ses)", "MP4 (Video - 720p)", "MP4 (Video - 360p)")
        AlertDialog.Builder(requireContext())
            .setTitle("Format Seçin")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startDownload(track, position, "mp3", null)
                    1 -> startDownload(track, position, "mp4", "720")
                    2 -> startDownload(track, position, "mp4", "360")
                }
            }.show()
    }

    private fun startDownload(track: Track, position: Int, format: String, quality: String?) {
        val ext = if (format == "mp3") "mp3" else "mp4"
        val fileName = "${track.name.take(50).replace(Regex("[/\\\\:*?\"<>|]"), "_")}.$ext"

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
            var legacyFile: File? = null
            var outputStream: OutputStream? = null

            try {
                val req = Request.Builder().url(url).build()
                val call = downloadClient.newCall(req)

                // İptal flag'i set edilince OkHttp call'ını da iptal et
                val cancelWatcher = launch {
                    while (true) {
                        if (cancelled.get()) { call.cancel(); break }
                        kotlinx.coroutines.delay(200)
                    }
                }

                val resp = call.execute()
                cancelWatcher.cancel() // bağlantı kuruldu, watcher'a gerek yok

                if (cancelled.get()) return@launch

                if (!resp.isSuccessful) {
                    val msg = when (resp.code) {
                        429  -> "Sunucu kapasitesi dolu, lütfen bekleyin"
                        503  -> "Sunucu geçici olarak kullanılamıyor"
                        else -> "İndirme başarısız (${resp.code})"
                    }
                    withContext(Dispatchers.Main) {
                        if (isAdded) Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                        trackAdapter?.cancelDownload(position)
                    }
                    return@launch
                }

                val body = resp.body ?: run {
                    withContext(Dispatchers.Main) { trackAdapter?.cancelDownload(position) }
                    return@launch
                }

                val totalBytes = body.contentLength()

                // OutputStream aç: Android 10+ MediaStore, Android 9- doğrudan dosya
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (format == "mp3") {
                        // MP3 → Müzik kütüphanesi
                        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/Melodify")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                        mediaStoreUri = ctx.contentResolver.insert(collection, values)
                            ?: throw Exception("MediaStore kaydı oluşturulamadı")
                    } else {
                        // MP4 → Download klasörü (tüm telefonlarda dosya yöneticisinde görünür)
                        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Melodify")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                        mediaStoreUri = ctx.contentResolver.insert(collection, values)
                            ?: throw Exception("MediaStore kaydı oluşturulamadı")
                    }
                    outputStream = ctx.contentResolver.openOutputStream(mediaStoreUri!!)
                        ?: throw Exception("OutputStream açılamadı")
                } else {
                    val dir = if (format == "mp3")
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                    else
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    dir.mkdirs()
                    legacyFile = File(dir, fileName)
                    outputStream = legacyFile.outputStream()
                }

                val notifBuilder = NotificationCompat.Builder(ctx, NOTIF_CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(track.name)
                    .setContentText("İndiriliyor...")
                    .setOngoing(true)
                    .setProgress(100, 0, totalBytes < 0)
                nm?.notify(notifId, notifBuilder.build())

                outputStream.use { out ->
                    val buf = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var lastUiUpdate = 0L
                    body.byteStream().use { inp ->
                        while (true) {
                            if (cancelled.get()) return@launch  // finally temizler
                            val read = inp.read(buf)
                            if (read == -1) break
                            out.write(buf, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastUiUpdate > 200 && totalBytes > 0) {
                                lastUiUpdate = now
                                val pct = ((downloaded * 100) / totalBytes).toInt()
                                withContext(Dispatchers.Main) { trackAdapter?.updateProgress(fakeId, pct) }
                                nm?.notify(notifId, notifBuilder
                                    .setProgress(100, pct, false)
                                    .setContentText("$pct%").build())
                            }
                        }
                    }
                }
                outputStream = null  // use {} kapattı, null yap ki finally tekrar kapatmasın

                // Başarılı: MediaStore kaydını görünür yap / MediaScanner tetikle
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    mediaStoreUri?.let { uri ->
                        ctx.contentResolver.update(uri, ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                        }, null, null)
                    }
                } else {
                    legacyFile?.let { f ->
                        MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), null) { path, _ ->
                            Log.d(TAG, "Scanned: $path")
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    trackAdapter?.markCompleted(fakeId)
                    if (isAdded) Toast.makeText(requireContext(), "✓ İndirildi: ${track.name}", Toast.LENGTH_SHORT).show()
                }
                nm?.notify(notifId, NotificationCompat.Builder(ctx, NOTIF_CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(track.name)
                    .setContentText("İndirme tamamlandı")
                    .setAutoCancel(true).build())

            } catch (e: Exception) {
                if (!cancelled.get()) {
                    Log.e(TAG, "İndirme hatası ${track.id}: ${e.message}")
                    withContext(Dispatchers.Main) {
                        if (isAdded) Toast.makeText(requireContext(), "Hata: ${e.message}", Toast.LENGTH_LONG).show()
                        trackAdapter?.cancelDownload(position)
                    }
                }
                // Hata veya iptal: yarım MediaStore kaydını temizle
                mediaStoreUri?.let { runCatching { ctx.contentResolver.delete(it, null, null) } }
                legacyFile?.runCatching { if (exists()) delete() }
            } finally {
                outputStream?.runCatching { close() }
                // Sadece iptal edilmişse yarım dosyayı sil, başarılı indirmede dokunma
                if (cancelled.get()) {
                    mediaStoreUri?.let { runCatching { ctx.contentResolver.delete(it, null, null) } }
                    legacyFile?.runCatching { delete() }
                }
                activeDownloads.remove(track.id)
                fakeIdToVideoId.remove(fakeId)
                nm?.cancel(notifId)
            }
        }

        activeDownloads[track.id] = Pair(job, cancelled)
    }

    private fun cancelDownloadByFakeId(fakeId: Long) {
        val videoId = fakeIdToVideoId[fakeId] ?: return
        val (job, cancelled) = activeDownloads[videoId] ?: return
        cancelled.set(true)
        job.cancel()
        // activeDownloads ve fakeIdToVideoId finally'de temizlenir
        val position = trackAdapter?.positionForFakeId(fakeId) ?: return
        trackAdapter?.cancelDownload(position)
    }

    // ─── Stream & Playlist ───────────────────────────────────

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
        val position = currentTracks.indexOf(track)
        if (position < 0 || trackAdapter?.isDownloaded(position) != true) {
            Toast.makeText(requireContext(), "Playlist'e sadece indirilmiş şarkılar eklenebilir", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val playlists = AppDatabase.getInstance(requireContext())
                .playlistDao().getAllPlaylists().first()
            if (playlists.isEmpty()) {
                Toast.makeText(requireContext(), "Önce Listeler sekmesinden playlist oluştur", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val names = playlists.map { it.name }.toTypedArray()
            AlertDialog.Builder(requireContext()).setItems(names) { _, i ->
                lifecycleScope.launch {
                    AppDatabase.getInstance(requireContext()).playlistSongDao().insertSong(
                        PlaylistSongEntity(
                            playlistId = playlists[i].id,
                            videoId = track.id,
                            title = track.name,
                            author = track.artistName,
                            thumbnail = track.image,
                            duration = track.duration
                        )
                    )
                    Toast.makeText(requireContext(), "\"${playlists[i].name}\" listesine eklendi", Toast.LENGTH_SHORT).show()
                }
            }.show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
