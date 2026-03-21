package com.example.musicplayer

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.example.musicplayer.databinding.ItemTrackBinding
import com.example.musicplayer.model.Track

private const val PAYLOAD_PROGRESS = "progress"
private const val PAYLOAD_PLAYING  = "playing"
private const val TYPE_TRACK       = 0
private const val TYPE_LOADING     = 1

class TrackAdapter(
    private val tracks: List<Track>,
    private val onTrackClick: (Track) -> Unit,
    private val onDownloadClick: (Track, Int) -> Unit,
    private val onLongClick: ((Track) -> Unit)? = null,
    private val onCancelDownload: ((Long) -> Unit)? = null,
    private val onSelectionChanged: ((Int) -> Unit)? = null  // seçili sayısı, -1=mod kapandı
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    // fakeId → position
    private val downloadMap = mutableMapOf<Long, Int>()
    // position → durum: null=yok, -2=hazırlanıyor, 0-100=yüzde, -1=tamamlandı
    private val progressMap = mutableMapOf<Int, Int>()
    private var playingPosition: Int = -1
    private var isLoadingMore = false

    // Çoklu seçim
    private val selectedPositions = mutableSetOf<Int>()
    var selectionMode = false
        private set

    private fun fakeIdForPosition(position: Int): Long? =
        downloadMap.entries.firstOrNull { it.value == position }?.key

    inner class TrackViewHolder(val binding: ItemTrackBinding) : RecyclerView.ViewHolder(binding.root)

    inner class LoadingViewHolder(view: View) : RecyclerView.ViewHolder(view)

    override fun getItemViewType(position: Int): Int =
        if (isLoadingMore && position == tracks.size) TYPE_LOADING else TYPE_TRACK

    override fun getItemCount() = tracks.size + if (isLoadingMore) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_LOADING) {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.activity_list_item, parent, false)
            // Basit loading view
            val pb = android.widget.ProgressBar(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 80)
                isIndeterminate = true
                indeterminateTintList = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor("#7C6FFF")
                )
            }
            val frame = android.widget.FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 80)
                setPadding(0, 16, 0, 16)
                addView(pb)
            }
            LoadingViewHolder(frame)
        } else {
            TrackViewHolder(ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: List<Any>) {
        if (holder is LoadingViewHolder) return
        if (payloads.isEmpty()) { onBindViewHolder(holder, position); return }
        val tvHolder = holder as TrackViewHolder
        payloads.forEach { payload ->
            when (payload) {
                PAYLOAD_PROGRESS -> bindDownloadState(tvHolder.binding, position, tracks[position])
                PAYLOAD_PLAYING  -> bindPlayingState(tvHolder.binding, position, tracks[position])
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is LoadingViewHolder) return
        val tvHolder = holder as TrackViewHolder
        val track = tracks[position]
        val isSelected = selectedPositions.contains(position)
        tvHolder.binding.apply {
            tvTrackName.text  = track.name
            tvArtistName.text = track.artistName
            tvDuration.text   = formatDuration(track.duration)

            // Seçim modu görünümü
            checkBox.visibility = if (selectionMode) View.VISIBLE else View.GONE
            checkBox.isChecked = isSelected
            checkBox.setOnClickListener { toggleSelection(position) }

            // Kart rengi seçime göre
            root.setCardBackgroundColor(
                if (isSelected) android.graphics.Color.parseColor("#1E1545")
                else android.graphics.Color.parseColor("#13131F")
            )
            if (isSelected) {
                root.strokeColor = android.graphics.Color.parseColor("#7C6FFF")
                root.strokeWidth = 2
            }

            // Kart giriş animasyonu
            root.alpha = 0f
            root.translationY = 24f
            root.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()

            root.setOnClickListener {
                if (selectionMode) {
                    toggleSelection(position)
                } else {
                    onTrackClick(track)
                }
            }

            root.setOnLongClickListener {
                if (!selectionMode) {
                    enterSelectionMode()
                    toggleSelection(position)
                } else {
                    // Seçim modunda uzun bas → playlist ekle (indirilmişse)
                    if (progressMap[position] == -1) onLongClick?.invoke(track)
                }
                true
            }

            btnDownload.setOnClickListener {
                if (selectionMode) return@setOnClickListener  // seçim modunda indir basılamaz
                val state = progressMap[position]
                when {
                    state != null && state >= -2 && state != -1 -> {
                        val fakeId = fakeIdForPosition(position)
                        if (fakeId != null) {
                            android.app.AlertDialog.Builder(holder.itemView.context)
                                .setTitle("İndirmeyi İptal Et")
                                .setMessage("\"${track.name}\" indirmesi iptal edilsin mi?")
                                .setPositiveButton("İptal Et") { _, _ -> onCancelDownload?.invoke(fakeId) }
                                .setNegativeButton("Devam Et", null)
                                .show()
                        }
                    }
                    state == -1 -> { /* zaten indirildi */ }
                    else -> onDownloadClick(track, position)
                }
            }
        }
        bindPlayingState(tvHolder.binding, position, track)
        bindDownloadState(tvHolder.binding, position, track)
    }

    private fun bindPlayingState(binding: ItemTrackBinding, position: Int, track: Track) {
        val isPlaying = position == playingPosition
        binding.apply {
            if (isPlaying) {
                ivAlbumArt.load(track.image) { transformations(RoundedCornersTransformation(10f)) }
                ivAlbumArt.setColorFilter(
                    android.graphics.Color.parseColor("#997C6FFF"),
                    android.graphics.PorterDuff.Mode.SRC_ATOP
                )
                root.setCardBackgroundColor(android.graphics.Color.parseColor("#1A1833"))
                root.strokeColor = android.graphics.Color.parseColor("#7C6FFF")
                root.strokeWidth = 2
                tvTrackName.setTextColor(android.graphics.Color.parseColor("#A89CFF"))

                // Çalan albüm kapağı pulse animasyonu
                val pulse = ObjectAnimator.ofFloat(ivAlbumArt, "alpha", 1f, 0.55f).apply {
                    duration = 700
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                    interpolator = LinearInterpolator()
                }
                ivAlbumArt.tag = pulse
                pulse.start()

                // Sol kenarda çalma çubuğu animasyonu (accent rengi)
                root.setCardBackgroundColor(android.graphics.Color.parseColor("#1A1833"))
            } else {
                ivAlbumArt.load(track.image) {
                    transformations(RoundedCornersTransformation(10f))
                    placeholder(android.R.drawable.ic_media_play)
                    error(android.R.drawable.ic_media_play)
                }
                ivAlbumArt.clearColorFilter()
                ivAlbumArt.alpha = 1f
                (ivAlbumArt.tag as? ObjectAnimator)?.cancel()
                ivAlbumArt.tag = null
                root.setCardBackgroundColor(android.graphics.Color.parseColor("#13131F"))
                root.strokeWidth = 0
                tvTrackName.setTextColor(android.graphics.Color.parseColor("#F0EEFF"))
            }
        }
    }

    private fun bindDownloadState(binding: ItemTrackBinding, position: Int, track: Track) {
        val progress = progressMap[position]
        binding.apply {
            when {
                progress == null -> {
                    // İndirilmedi — mor çerçeveli yuvarlak, tint sıfırla
                    downloadProgress.visibility = View.GONE
                    tvDownloadPercent.visibility = View.GONE
                    btnDownload.setImageResource(android.R.drawable.ic_menu_save)
                    btnDownload.setColorFilter(android.graphics.Color.parseColor("#7C6FFF"), android.graphics.PorterDuff.Mode.SRC_IN)
                    btnDownload.setBackgroundResource(com.example.musicplayer.R.drawable.bg_download_button)
                    btnDownload.backgroundTintList = null  // önceki kırmızı/yeşil tint'i temizle
                    btnDownload.animation?.cancel()
                    btnDownload.clearAnimation()
                    btnDownload.isEnabled = true
                    btnDownload.alpha = 1f
                    btnDownload.scaleX = 1f
                    btnDownload.scaleY = 1f
                }
                progress == -2 -> {
                    // Hazırlanıyor — dönen ikon, pembe-mor
                    downloadProgress.visibility = View.VISIBLE
                    downloadProgress.isIndeterminate = true
                    tvDownloadPercent.visibility = View.VISIBLE
                    tvDownloadPercent.text = "Hazırlanıyor…"
                    btnDownload.setImageResource(android.R.drawable.ic_popup_sync)
                    btnDownload.setColorFilter(android.graphics.Color.parseColor("#C961FF"), android.graphics.PorterDuff.Mode.SRC_IN)
                    btnDownload.setBackgroundResource(com.example.musicplayer.R.drawable.bg_download_button)
                    btnDownload.isEnabled = true
                    if (btnDownload.animation == null) {
                        btnDownload.startAnimation(android.view.animation.RotateAnimation(
                            0f, 360f,
                            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f
                        ).apply {
                            duration = 900
                            repeatCount = android.view.animation.Animation.INFINITE
                            interpolator = LinearInterpolator()
                        })
                    }
                }
                progress == -1 -> {
                    // Tamamlandı — yeşil dolu daire
                    downloadProgress.visibility = View.GONE
                    downloadProgress.isIndeterminate = false
                    tvDownloadPercent.visibility = View.VISIBLE
                    tvDownloadPercent.text = "✓ İndirildi"
                    tvDownloadPercent.setTextColor(android.graphics.Color.parseColor("#3DCC6B"))
                    btnDownload.setImageResource(android.R.drawable.checkbox_on_background)
                    btnDownload.setColorFilter(android.graphics.Color.parseColor("#FFFFFF"), android.graphics.PorterDuff.Mode.SRC_IN)
                    btnDownload.setBackgroundResource(com.example.musicplayer.R.drawable.bg_play_button)
                    btnDownload.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#3DCC6B"))
                    btnDownload.animation?.cancel()
                    btnDownload.clearAnimation()
                    btnDownload.isEnabled = false
                    // Scale animasyonu
                    btnDownload.scaleX = 0.5f; btnDownload.scaleY = 0.5f
                    btnDownload.animate().scaleX(1f).scaleY(1f).setDuration(280)
                        .setInterpolator(AccelerateDecelerateInterpolator()).start()
                }
                else -> {
                    // İndiriliyor (0-100) — kırmızı iptal butonu
                    downloadProgress.visibility = View.VISIBLE
                    downloadProgress.isIndeterminate = false
                    downloadProgress.progress = progress
                    tvDownloadPercent.visibility = View.VISIBLE
                    tvDownloadPercent.text = "✕ İptal  %$progress"
                    tvDownloadPercent.setTextColor(android.graphics.Color.parseColor("#7C6FFF"))
                    btnDownload.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                    btnDownload.setColorFilter(android.graphics.Color.parseColor("#FFFFFF"), android.graphics.PorterDuff.Mode.SRC_IN)
                    btnDownload.setBackgroundResource(com.example.musicplayer.R.drawable.bg_play_button)
                    btnDownload.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FF4F6B"))
                    btnDownload.animation?.cancel()
                    btnDownload.clearAnimation()
                    btnDownload.isEnabled = true
                }
            }
        }
    }

    // ─── Seçim modu ──────────────────────────────────────────

    fun enterSelectionMode() {
        selectionMode = true
        notifyDataSetChanged()
        onSelectionChanged?.invoke(0)
    }

    fun exitSelectionMode() {
        selectionMode = false
        selectedPositions.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(-1)
    }

    private fun toggleSelection(position: Int) {
        if (selectedPositions.contains(position)) selectedPositions.remove(position)
        else selectedPositions.add(position)
        notifyItemChanged(position)
        onSelectionChanged?.invoke(selectedPositions.size)
        if (selectedPositions.isEmpty() && selectionMode) exitSelectionMode()
    }

    fun selectAll() {
        tracks.indices.forEach { selectedPositions.add(it) }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedPositions.size)
    }

    fun getSelectedTracks(): List<Track> =
        selectedPositions.sorted().mapNotNull { tracks.getOrNull(it) }

    fun getSelectedPositions(): List<Int> = selectedPositions.sorted()

    // ─── Loading footer ───────────────────────────────────────

    fun setLoadingMore(loading: Boolean) {
        val wasLoading = isLoadingMore
        isLoadingMore = loading
        if (loading && !wasLoading) {
            notifyItemInserted(tracks.size)
        } else if (!loading && wasLoading) {
            notifyItemRemoved(tracks.size)
        }
    }

    fun setPlayingPosition(position: Int) {
        val old = playingPosition; playingPosition = position
        if (old >= 0 && old < tracks.size) notifyItemChanged(old, PAYLOAD_PLAYING)
        if (position >= 0 && position < tracks.size) notifyItemChanged(position, PAYLOAD_PLAYING)
    }

    fun registerPreparing(position: Int) {
        progressMap[position] = -2
        notifyItemChanged(position, PAYLOAD_PROGRESS)
    }

    fun registerDownload(fakeId: Long, position: Int) {
        downloadMap[fakeId] = position
        progressMap[position] = 0
        notifyItemChanged(position, PAYLOAD_PROGRESS)
    }

    fun updateProgress(fakeId: Long, percent: Int) {
        val position = downloadMap[fakeId] ?: return
        progressMap[position] = percent
        notifyItemChanged(position, PAYLOAD_PROGRESS)
    }

    fun markCompleted(fakeId: Long) {
        val position = downloadMap[fakeId] ?: return
        progressMap[position] = -1
        downloadMap.remove(fakeId)
        notifyItemChanged(position, PAYLOAD_PROGRESS)
    }

    fun markCompletedByPosition(position: Int) {
        if (progressMap[position] == -1) return
        progressMap[position] = -1
        notifyItemChanged(position, PAYLOAD_PROGRESS)
    }

    fun positionForFakeId(fakeId: Long): Int? = downloadMap[fakeId]

    fun isDownloaded(position: Int): Boolean = progressMap[position] == -1

    fun cancelDownload(position: Int) {
        progressMap.remove(position)
        downloadMap.entries.removeIf { it.value == position }
        notifyItemChanged(position, PAYLOAD_PROGRESS)
    }

    private fun formatDuration(seconds: Int): String {
        val m = seconds / 60; val s = seconds % 60
        return "%d:%02d".format(m, s)
    }
}
