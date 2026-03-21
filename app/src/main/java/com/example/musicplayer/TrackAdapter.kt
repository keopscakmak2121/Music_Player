package com.example.musicplayer

import android.animation.ObjectAnimator
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
    private val onPlayClick: (Track, Int) -> Unit, // Yeni: Play butonu için
    private val onLongClick: ((Track) -> Unit)? = null,
    private val onCancelDownload: ((Long) -> Unit)? = null,
    private val onSelectionChanged: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val downloadMap = mutableMapOf<Long, Int>()
    private val progressMap = mutableMapOf<Int, Int>()
    private var playingPosition: Int = -1
    private var isLoadingMore = false

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
            val pb = android.widget.ProgressBar(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 80)
                isIndeterminate = true
            }
            LoadingViewHolder(pb)
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

            checkBox.visibility = if (selectionMode) View.VISIBLE else View.GONE
            checkBox.isChecked = isSelected
            checkBox.setOnClickListener { toggleSelection(position) }

            root.setCardBackgroundColor(
                if (isSelected) android.graphics.Color.parseColor("#1E1545")
                else android.graphics.Color.parseColor("#13131F")
            )

            // Satıra tıklama artık sadece SEÇİM yapar, çalmaz
            root.setOnClickListener {
                if (selectionMode) {
                    toggleSelection(position)
                } else {
                    // Hiçbir şey yapma veya seçimi başlat
                }
            }

            root.setOnLongClickListener {
                if (!selectionMode) {
                    enterSelectionMode()
                    toggleSelection(position)
                }
                true
            }

            // OYNA BUTONU - Akıllı çalma
            btnPlay.setOnClickListener {
                if (selectionMode) return@setOnClickListener
                onPlayClick(track, position)
            }

            btnDownload.setOnClickListener {
                if (selectionMode) return@setOnClickListener
                val state = progressMap[position]
                when {
                    state != null && state >= -2 && state != -1 -> {
                        val fakeId = fakeIdForPosition(position)
                        if (fakeId != null) onCancelDownload?.invoke(fakeId)
                    }
                    state == -1 -> { /* İndirildi */ }
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
                btnPlay.setImageResource(android.R.drawable.ic_media_pause)
                root.strokeWidth = 2
                root.strokeColor = android.graphics.Color.parseColor("#7C6FFF")
            } else {
                ivAlbumArt.load(track.image) { transformations(RoundedCornersTransformation(10f)) }
                btnPlay.setImageResource(android.R.drawable.ic_media_play)
                root.strokeWidth = 0
            }
        }
    }

    private fun bindDownloadState(binding: ItemTrackBinding, position: Int, track: Track) {
        val progress = progressMap[position]
        binding.apply {
            when {
                progress == null -> {
                    downloadProgress.visibility = View.GONE
                    tvDownloadPercent.visibility = View.GONE
                    btnDownload.setImageResource(android.R.drawable.ic_menu_save)
                    btnDownload.isEnabled = true
                }
                progress == -2 -> {
                    downloadProgress.visibility = View.VISIBLE
                    downloadProgress.isIndeterminate = true
                    tvDownloadPercent.visibility = View.VISIBLE
                    tvDownloadPercent.text = "Bekliyor..."
                }
                progress == -1 -> {
                    downloadProgress.visibility = View.GONE
                    tvDownloadPercent.visibility = View.VISIBLE
                    tvDownloadPercent.text = "✓ İndirildi"
                    btnDownload.setImageResource(android.R.drawable.checkbox_on_background)
                    btnDownload.isEnabled = false
                }
                else -> {
                    downloadProgress.visibility = View.VISIBLE
                    downloadProgress.isIndeterminate = false
                    downloadProgress.progress = progress
                    tvDownloadPercent.visibility = View.VISIBLE
                    tvDownloadPercent.text = "%$progress"
                }
            }
        }
    }

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

    fun getSelectedTracks(): List<Track> = selectedPositions.sorted().mapNotNull { tracks.getOrNull(it) }

    fun setLoadingMore(loading: Boolean) {
        isLoadingMore = loading
        notifyDataSetChanged()
    }

    fun setPlayingPosition(position: Int) {
        val old = playingPosition
        playingPosition = position
        if (old >= 0) notifyItemChanged(old, PAYLOAD_PLAYING)
        if (position >= 0) notifyItemChanged(position, PAYLOAD_PLAYING)
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
        progressMap[position] = -1
        notifyItemChanged(position, PAYLOAD_PROGRESS)
    }

    fun positionForFakeId(fakeId: Long): Int? = downloadMap[fakeId]
    fun isDownloaded(position: Int): Boolean = progressMap[position] == -1
    fun cancelDownload(position: Int) {
        progressMap.remove(position)
        notifyItemChanged(position, PAYLOAD_PROGRESS)
    }

    private fun formatDuration(seconds: Int): String {
        val m = seconds / 60; val s = seconds % 60
        return "%d:%02d".format(m, s)
    }
}
