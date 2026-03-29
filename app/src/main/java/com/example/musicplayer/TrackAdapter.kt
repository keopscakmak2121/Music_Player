package com.example.musicplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
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
    private val onPlayClick: (Track, Int) -> Unit,
    private val onAddToPlaylistClick: (Track) -> Unit,
    private val onLongClick: ((Track) -> Unit)? = null,
    private val onCancelDownload: ((Long) -> Unit)? = null,
    private val onSelectionChanged: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val downloadMap = mutableMapOf<Long, Int>()
    private val progressMap = mutableMapOf<Int, Int>()
    private val playlistMap = mutableMapOf<Int, Boolean>() // Pozisyon -> Listede mi?
    private var playingPosition: Int = -1
    private var isLoadingMore = false

    private val selectedPositions = mutableSetOf<Int>()
    var selectionMode = false
        private set

    inner class TrackViewHolder(val binding: ItemTrackBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int =
        if (isLoadingMore && position == tracks.size) TYPE_LOADING else TYPE_TRACK

    override fun getItemCount() = tracks.size + if (isLoadingMore) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_LOADING) {
            val pb = android.widget.ProgressBar(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 80)
            }
            object : RecyclerView.ViewHolder(pb) {}
        } else {
            TrackViewHolder(ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder !is TrackViewHolder) return
        val track = tracks[position]
        val isSelected = selectedPositions.contains(position)
        val context = holder.itemView.context
        
        holder.binding.apply {
            tvTrackName.text  = track.name
            tvArtistName.text = track.artistName
            tvDuration.text   = formatDuration(track.duration)

            ivAlbumArt.load(track.image.ifEmpty { "https://via.placeholder.com/150" }) {
                crossfade(true)
                transformations(RoundedCornersTransformation(14f))
            }

            checkBox.visibility = if (selectionMode) View.VISIBLE else View.GONE
            checkBox.isChecked = isSelected
            
            root.setCardBackgroundColor(
                if (isSelected) ContextCompat.getColor(context, R.color.bg_elevated)
                else ContextCompat.getColor(context, R.color.bg_card)
            )
            root.strokeWidth = if (isSelected || position == playingPosition) 3 else 0
            root.strokeColor = ContextCompat.getColor(context, R.color.accent)

            root.setOnClickListener {
                if (selectionMode) toggleSelection(position)
                else onPlayClick(track, position)
            }

            root.setOnLongClickListener {
                if (!selectionMode) { enterSelectionMode(); toggleSelection(position) }
                true
            }

            btnDownload.setOnClickListener {
                if (selectionMode) return@setOnClickListener
                onDownloadClick(track, position)
            }

            btnAddToPlaylist.setOnClickListener {
                if (selectionMode) return@setOnClickListener
                onAddToPlaylistClick(track)
            }

            // Listede mi kontrolü
            val isInPlaylist = playlistMap[position] ?: false
            btnAddToPlaylist.setImageResource(
                if (isInPlaylist) android.R.drawable.checkbox_on_background 
                else android.R.drawable.ic_menu_add
            )
            btnAddToPlaylist.isEnabled = !isInPlaylist
        }
        bindPlayingState(holder.binding, position)
        bindDownloadState(holder.binding, position)
    }

    private fun bindPlayingState(binding: ItemTrackBinding, position: Int) {
        val isPlaying = position == playingPosition && PlayerManager.isPlaying()
        binding.btnPlay.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause 
            else android.R.drawable.ic_media_play
        )
    }

    private fun bindDownloadState(binding: ItemTrackBinding, position: Int) {
        val progress = progressMap[position]
        binding.apply {
            when {
                progress == null -> {
                    downloadProgress.visibility = View.GONE
                    tvDownloadPercent.visibility = View.GONE
                    btnDownload.setImageResource(android.R.drawable.stat_sys_download)
                    btnDownload.isEnabled = true
                    btnPlay.isEnabled = true
                }
                progress == -3 -> { // Hazırlanıyor... (Play'e basınca)
                    downloadProgress.visibility = View.VISIBLE
                    downloadProgress.isIndeterminate = true
                    tvDownloadPercent.visibility = View.VISIBLE
                    tvDownloadPercent.text = "Hazırlanıyor..."
                    btnDownload.isEnabled = false
                    btnPlay.isEnabled = false
                }
                progress == -2 -> { // Bekliyor... (İndirmeye basınca)
                    downloadProgress.visibility = View.VISIBLE
                    downloadProgress.isIndeterminate = true
                    tvDownloadPercent.visibility = View.VISIBLE
                    tvDownloadPercent.text = "Bekliyor..."
                    btnDownload.isEnabled = true 
                }
                progress == -1 -> { // İndirildi
                    downloadProgress.visibility = View.GONE
                    tvDownloadPercent.visibility = View.VISIBLE
                    tvDownloadPercent.text = "✓ İndirildi"
                    btnDownload.setImageResource(android.R.drawable.checkbox_on_background)
                    btnDownload.isEnabled = false
                    btnPlay.isEnabled = true
                }
                else -> { // İndiriliyor %...
                    downloadProgress.visibility = View.VISIBLE
                    downloadProgress.isIndeterminate = false
                    downloadProgress.progress = progress
                    tvDownloadPercent.visibility = View.VISIBLE
                    tvDownloadPercent.text = "%$progress"
                    btnDownload.isEnabled = true
                }
            }
        }
    }

    // --- Selection and Helper Methods ---
    private fun toggleSelection(position: Int) {
        if (selectedPositions.contains(position)) selectedPositions.remove(position)
        else selectedPositions.add(position)
        notifyItemChanged(position)
        onSelectionChanged?.invoke(selectedPositions.size)
        if (selectedPositions.isEmpty() && selectionMode) exitSelectionMode()
    }

    fun enterSelectionMode() { selectionMode = true; notifyDataSetChanged(); onSelectionChanged?.invoke(0) }
    fun exitSelectionMode() { selectionMode = false; selectedPositions.clear(); notifyDataSetChanged(); onSelectionChanged?.invoke(-1) }
    fun selectAll() { tracks.indices.forEach { selectedPositions.add(it) }; notifyDataSetChanged(); onSelectionChanged?.invoke(selectedPositions.size) }
    fun getSelectedTracks(): List<Track> = selectedPositions.sorted().mapNotNull { tracks.getOrNull(it) }
    fun setLoadingMore(loading: Boolean) { isLoadingMore = loading; notifyDataSetChanged() }

    fun setPlayingPosition(position: Int) {
        val old = playingPosition
        playingPosition = position
        if (old >= 0) notifyItemChanged(old)
        if (position >= 0) notifyItemChanged(position)
    }

    fun notifyPlayingStateChanged() { if (playingPosition >= 0) notifyItemChanged(playingPosition) }
    
    fun registerLoading(position: Int) { progressMap[position] = -3; notifyItemChanged(position) }
    fun clearLoading(position: Int) { if (progressMap[position] == -3) progressMap.remove(position); notifyItemChanged(position) }
    fun registerPreparing(position: Int) { progressMap[position] = -2; notifyItemChanged(position) }
    fun registerDownload(fakeId: Long, position: Int) { downloadMap[fakeId] = position; progressMap[position] = 0; notifyItemChanged(position) }
    fun updateProgress(fakeId: Long, percent: Int) { val pos = downloadMap[fakeId] ?: return; progressMap[pos] = percent; notifyItemChanged(pos) }
    fun markCompleted(fakeId: Long) { val pos = downloadMap[fakeId] ?: return; progressMap[pos] = -1; notifyItemChanged(pos) }
    fun markCompletedByPosition(position: Int) { progressMap[position] = -1; notifyItemChanged(position) }
    fun isDownloaded(position: Int): Boolean = progressMap[position] == -1
    fun cancelDownload(position: Int) { progressMap.remove(position); notifyItemChanged(position) }
    fun positionForFakeId(fakeId: Long) = downloadMap[fakeId]

    fun markInPlaylist(position: Int) {
        playlistMap[position] = true
        notifyItemChanged(position)
    }

    fun setPlaylistMap(map: Map<Int, Boolean>) {
        playlistMap.clear()
        playlistMap.putAll(map)
        notifyDataSetChanged()
    }

    private fun formatDuration(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)
}
