package com.example.musicplayer.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.example.musicplayer.R
import com.example.musicplayer.api.InvidiousSearchResult
import com.example.musicplayer.api.PlaylistInfoResponse
import com.example.musicplayer.api.YouTubeApi
import com.example.musicplayer.db.AppDatabase
import com.example.musicplayer.db.PlaylistEntity
import com.example.musicplayer.db.PlaylistSongEntity
import com.example.musicplayer.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class PlaylistImportFragment : Fragment() {

    // Dışarıdan set edilecek callback'ler
    var onBack: (() -> Unit)? = null
    var onDownloadTrack: ((Track) -> Unit)? = null

    private lateinit var youtubeApi: YouTubeApi

    // UI elemanları (inflate sonrası atanır)
    private lateinit var btnBack: ImageButton
    private lateinit var btnLoad: Button
    private lateinit var etPlaylistUrl: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var tvPlaylistTitle: TextView
    private lateinit var tvPlaylistInfo: TextView
    private lateinit var ivPlaylistCover: ImageView
    private lateinit var headerLayout: LinearLayout
    private lateinit var btnSelectAll: Button
    private lateinit var btnDownloadSelected: Button
    private lateinit var btnAddToPlaylist: Button
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView

    private var playlistData: PlaylistInfoResponse? = null
    private val selectedIds = mutableSetOf<String>()
    private var importAdapter: PlaylistImportAdapter? = null

    companion object {
        private const val BASE_URL = "http://100.122.252.85:5050/"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Programmatik layout — XML dosyası gerektirmez
        return buildLayout(inflater.context)
    }

    private fun buildLayout(ctx: android.content.Context): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        // Toolbar satırı
        val toolbar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        btnBack = ImageButton(ctx).apply {
            setImageResource(android.R.drawable.ic_media_previous)
            background = null
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val tvTitle = TextView(ctx).apply {
            text = "YouTube Playlist İçe Aktar"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val hp = (8 * resources.displayMetrics.density).toInt()
            setPadding(hp, 0, 0, 0)
        }
        toolbar.addView(btnBack)
        toolbar.addView(tvTitle)
        root.addView(toolbar)

        // URL giriş satırı
        val urlRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val vp = (12 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = vp }
        }
        etPlaylistUrl = EditText(ctx).apply {
            hint = "YouTube playlist URL veya ID girin"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        btnLoad = Button(ctx).apply {
            text = "Getir"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        urlRow.addView(etPlaylistUrl)
        urlRow.addView(btnLoad)
        root.addView(urlRow)

        // Progress bar
        progressBar = ProgressBar(ctx).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                val vp = (8 * resources.displayMetrics.density).toInt()
                topMargin = vp
            }
        }
        root.addView(progressBar)

        // Playlist header (cover + başlık + bilgi)
        headerLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            val vp = (12 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = vp }
        }
        val coverSize = (72 * resources.displayMetrics.density).toInt()
        ivPlaylistCover = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(coverSize, coverSize)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val headerText = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val hp = (12 * resources.displayMetrics.density).toInt()
            setPadding(hp, 0, 0, 0)
        }
        tvPlaylistTitle = TextView(ctx).apply {
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        tvPlaylistInfo = TextView(ctx).apply {
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        headerText.addView(tvPlaylistTitle)
        headerText.addView(tvPlaylistInfo)
        headerLayout.addView(ivPlaylistCover)
        headerLayout.addView(headerText)
        root.addView(headerLayout)

        // Aksiyon butonları
        val actionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val vp = (8 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = vp }
            visibility = View.GONE
            tag = "actionRow"
        }
        btnSelectAll = Button(ctx).apply {
            text = "Tümünü Seç"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            textSize = 12f
        }
        btnDownloadSelected = Button(ctx).apply {
            text = "Seçilenleri İndir"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            textSize = 12f
        }
        btnAddToPlaylist = Button(ctx).apply {
            text = "Playlist'e Ekle"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            textSize = 12f
        }
        actionRow.addView(btnSelectAll)
        actionRow.addView(btnDownloadSelected)
        actionRow.addView(btnAddToPlaylist)
        root.addView(actionRow)

        // Boş durum metni
        tvEmpty = TextView(ctx).apply {
            text = "Playlist URL'ini girin ve 'Getir' butonuna basın"
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            visibility = View.VISIBLE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                val vp = (24 * resources.displayMetrics.density).toInt()
                topMargin = vp
            }
        }
        root.addView(tvEmpty)

        // RecyclerView
        recyclerView = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                val vp = (8 * resources.displayMetrics.density).toInt()
                topMargin = vp
            }
            visibility = View.GONE
        }
        root.addView(recyclerView)

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRetrofit()
        setupListeners()
    }

    private fun setupRetrofit() {
        youtubeApi = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(YouTubeApi::class.java)
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { onBack?.invoke() }

        btnLoad.setOnClickListener {
            val input = etPlaylistUrl.text.toString().trim()
            if (input.isEmpty()) {
                Toast.makeText(requireContext(), "URL veya ID girin", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val playlistId = extractPlaylistId(input)
            if (playlistId == null) {
                Toast.makeText(requireContext(), "Geçersiz playlist URL'i", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            loadPlaylist(playlistId)
        }

        btnSelectAll.setOnClickListener {
            val tracks = playlistData?.tracks ?: return@setOnClickListener
            if (selectedIds.size == tracks.size) {
                // Hepsini kaldır
                selectedIds.clear()
                btnSelectAll.text = "Tümünü Seç"
            } else {
                // Hepsini seç
                selectedIds.addAll(tracks.map { it.videoId })
                btnSelectAll.text = "Seçimi Kaldır"
            }
            importAdapter?.notifyDataSetChanged()
        }

        btnDownloadSelected.setOnClickListener {
            if (selectedIds.isEmpty()) {
                Toast.makeText(requireContext(), "Önce şarkı seçin", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val tracks = playlistData?.tracks ?: return@setOnClickListener
            val toDownload = tracks.filter { it.videoId in selectedIds }
            toDownload.forEach { result ->
                val track = result.toTrack()
                onDownloadTrack?.invoke(track)
            }
            Toast.makeText(
                requireContext(),
                "${toDownload.size} şarkı indirme kuyruğuna eklendi",
                Toast.LENGTH_SHORT
            ).show()
        }

        btnAddToPlaylist.setOnClickListener {
            if (selectedIds.isEmpty()) {
                Toast.makeText(requireContext(), "Önce şarkı seçin", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showAddToPlaylistDialog()
        }
    }

    // YouTube URL'inden playlist ID'sini çıkar
    private fun extractPlaylistId(input: String): String? {
        if (input.startsWith("PL") || input.startsWith("RD") || input.startsWith("UU")) {
            return input
        }
        val regex = Regex("[?&]list=([A-Za-z0-9_-]+)")
        return regex.find(input)?.groupValues?.get(1)
    }

    fun loadPlaylist(playlistId: String) {
        if (!isAdded) return
        progressBar.visibility = View.VISIBLE
        btnLoad.isEnabled = false
        tvEmpty.visibility = View.GONE
        recyclerView.visibility = View.GONE
        headerLayout.visibility = View.GONE
        view?.findViewWithTag<LinearLayout>("actionRow")?.visibility = View.GONE

        youtubeApi.getPlaylist(playlistId).enqueue(object : Callback<PlaylistInfoResponse> {
            override fun onResponse(
                call: Call<PlaylistInfoResponse>,
                response: Response<PlaylistInfoResponse>
            ) {
                if (!isAdded) return
                progressBar.visibility = View.GONE
                btnLoad.isEnabled = true

                val data = response.body()
                if (!response.isSuccessful || data == null) {
                    tvEmpty.text = "Playlist yüklenemedi"
                    tvEmpty.visibility = View.VISIBLE
                    return
                }

                playlistData = data
                selectedIds.clear()
                showPlaylist(data)
            }

            override fun onFailure(call: Call<PlaylistInfoResponse>, t: Throwable) {
                if (!isAdded) return
                progressBar.visibility = View.GONE
                btnLoad.isEnabled = true
                tvEmpty.text = "Bağlantı hatası: ${t.message}"
                tvEmpty.visibility = View.VISIBLE
            }
        })
    }

    private fun showPlaylist(data: PlaylistInfoResponse) {
        // Header
        tvPlaylistTitle.text = data.title
        tvPlaylistInfo.text = "${data.author} · ${data.tracks.size} şarkı"
        if (data.thumbnail.isNotEmpty()) {
            ivPlaylistCover.load(data.thumbnail) {
                transformations(RoundedCornersTransformation(8f))
                placeholder(android.R.drawable.ic_menu_gallery)
            }
        }
        headerLayout.visibility = View.VISIBLE

        // Aksiyon satırı
        view?.findViewWithTag<LinearLayout>("actionRow")?.visibility = View.VISIBLE

        // Adapter
        importAdapter = PlaylistImportAdapter(
            tracks = data.tracks,
            selectedIds = selectedIds,
            onToggle = { id ->
                if (selectedIds.contains(id)) selectedIds.remove(id)
                else selectedIds.add(id)
                // Tümünü seç butonunu güncelle
                btnSelectAll.text =
                    if (selectedIds.size == data.tracks.size) "Seçimi Kaldır" else "Tümünü Seç"
            }
        )
        recyclerView.adapter = importAdapter
        recyclerView.visibility = View.VISIBLE
    }

    private fun showAddToPlaylistDialog() {
        lifecycleScope.launch {
            val db = AppDatabase.getInstance(requireContext())
            val playlists = withContext(Dispatchers.IO) {
                // Flow'dan tek seferlik liste al
                var result = emptyList<com.example.musicplayer.db.PlaylistEntity>()
                val job = launch(Dispatchers.IO) {
                    db.playlistDao().getAllPlaylists().collect {
                        result = it
                    }
                }
                // İlk emit'i al ve durdur
                kotlinx.coroutines.delay(100)
                job.cancel()
                result
            }

            if (playlists.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Önce bir playlist oluşturun", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val names = playlists.map { it.name }.toTypedArray()
            withContext(Dispatchers.Main) {
                android.app.AlertDialog.Builder(requireContext())
                    .setTitle("Hangi playlist'e eklensin?")
                    .setItems(names) { _, which ->
                        val playlist = playlists[which]
                        addSelectedToPlaylist(playlist)
                    }
                    .setNegativeButton("İptal", null)
                    .show()
            }
        }
    }

    private fun addSelectedToPlaylist(playlist: PlaylistEntity) {
        val tracks = playlistData?.tracks ?: return
        val toAdd = tracks.filter { it.videoId in selectedIds }

        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getInstance(requireContext())
            var added = 0
            toAdd.forEach { result ->
                val alreadyIn = db.playlistSongDao().isSongInPlaylist(playlist.id, result.videoId) > 0
                if (!alreadyIn) {
                    db.playlistSongDao().insertSong(
                        PlaylistSongEntity(
                            playlistId = playlist.id,
                            videoId = result.videoId,
                            title = result.title,
                            author = result.author,
                            thumbnail = result.thumbnails,
                            duration = result.duration
                        )
                    )
                    added++
                }
            }
            withContext(Dispatchers.Main) {
                val skipped = toAdd.size - added
                val msg = if (skipped > 0)
                    "$added şarkı eklendi, $skipped zaten listede"
                else
                    "$added şarkı '${playlist.name}' listesine eklendi"
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    // InvidiousSearchResult -> Track dönüşümü (download için)
    private fun InvidiousSearchResult.toTrack() = Track(
        id = videoId,
        name = title,
        artistName = author,
        image = thumbnails,
        audio = "",
        duration = duration,
        videoId = videoId
    )
}

// ─── Adapter ──────────────────────────────────────────────────────────────────

class PlaylistImportAdapter(
    private val tracks: List<InvidiousSearchResult>,
    private val selectedIds: MutableSet<String>,
    private val onToggle: (String) -> Unit
) : RecyclerView.Adapter<PlaylistImportAdapter.VH>() {

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val checkbox: CheckBox = itemView.findViewWithTag("cb")
        val ivThumb: ImageView = itemView.findViewWithTag("thumb")
        val tvTitle: TextView = itemView.findViewWithTag("title")
        val tvAuthor: TextView = itemView.findViewWithTag("author")
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val dp = ctx.resources.displayMetrics.density

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val vp = (10 * dp).toInt()
            val hp = (4 * dp).toInt()
            setPadding(hp, vp, hp, vp)
        }

        val cb = CheckBox(ctx).apply {
            tag = "cb"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val thumbSize = (48 * dp).toInt()
        val iv = ImageView(ctx).apply {
            tag = "thumb"
            layoutParams = LinearLayout.LayoutParams(thumbSize, thumbSize).apply {
                val hp = (8 * dp).toInt()
                setMargins(hp, 0, hp, 0)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvT = TextView(ctx).apply {
            tag = "title"
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val tvA = TextView(ctx).apply {
            tag = "author"
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        textCol.addView(tvT)
        textCol.addView(tvA)

        row.addView(cb)
        row.addView(iv)
        row.addView(textCol)

        return VH(row)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val track = tracks[position]
        holder.tvTitle.text = track.title
        holder.tvAuthor.text = track.author
        holder.checkbox.isChecked = track.videoId in selectedIds

        if (track.thumbnails.isNotEmpty()) {
            holder.ivThumb.load(track.thumbnails) {
                transformations(RoundedCornersTransformation(6f))
                placeholder(android.R.drawable.ic_menu_gallery)
            }
        }

        // Satıra tıklayınca checkbox toggle
        holder.itemView.setOnClickListener {
            onToggle(track.videoId)
            holder.checkbox.isChecked = track.videoId in selectedIds
        }
        holder.checkbox.setOnClickListener {
            onToggle(track.videoId)
        }
    }

    override fun getItemCount() = tracks.size
}