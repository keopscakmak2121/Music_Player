package com.example.musicplayer.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.musicplayer.R
import com.example.musicplayer.databinding.ItemDownloadedTrackBinding

class DownloadedTrackAdapter(
    private val files: MutableList<LocalFile>,
    private val onPlayClick: (LocalFile) -> Unit,
    private val onDeleteClick: (LocalFile, Int) -> Unit,
    private val onAddToPlaylist: (LocalFile) -> Unit,
    private val onSelectionChanged: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<DownloadedTrackAdapter.ViewHolder>() {

    private val selectedPositions = mutableSetOf<Int>()
    var selectionMode = false
        private set

    class ViewHolder(val binding: ItemDownloadedTrackBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDownloadedTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        val isSelected = selectedPositions.contains(position)
        val context = holder.itemView.context
        
        holder.binding.apply {
            tvFileName.text = file.name
            tvFileSize.text = formatSize(file.size)

            ivMusicIcon.setImageResource(android.R.drawable.ic_media_play)
            ivMusicIcon.setColorFilter(ContextCompat.getColor(context, R.color.accent))
            ivMusicIcon.setPadding(12, 12, 12, 12)

            root.setCardBackgroundColor(
                if (isSelected) ContextCompat.getColor(context, R.color.bg_elevated)
                else ContextCompat.getColor(context, R.color.bg_card)
            )
            root.strokeWidth = if (isSelected) 3 else 0
            root.strokeColor = ContextCompat.getColor(context, R.color.accent)

            checkBox.visibility = if (selectionMode) View.VISIBLE else View.GONE
            checkBox.isChecked = isSelected
            btnDelete.visibility = if (selectionMode) View.GONE else View.VISIBLE

            root.setOnClickListener {
                if (selectionMode) toggleSelection(position)
                else onPlayClick(file)
            }
            
            checkBox.setOnClickListener { toggleSelection(position) }
            btnDelete.setOnClickListener { onDeleteClick(file, holder.adapterPosition) }

            root.setOnLongClickListener {
                if (!selectionMode) {
                    enterSelectionMode()
                    toggleSelection(position)
                } else {
                    onAddToPlaylist(file)
                }
                true
            }
        }
    }

    override fun getItemCount() = files.size

    private fun toggleSelection(position: Int) {
        if (selectedPositions.contains(position)) selectedPositions.remove(position)
        else selectedPositions.add(position)
        notifyItemChanged(position)
        onSelectionChanged?.invoke(selectedPositions.size)
        if (selectedPositions.isEmpty() && selectionMode) exitSelectionMode()
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

    fun selectAll() {
        selectedPositions.clear()
        files.indices.forEach { selectedPositions.add(it) }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedPositions.size)
    }

    fun getSelectedFiles(): List<LocalFile> = selectedPositions.sorted().mapNotNull { files.getOrNull(it) }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000     -> "%.0f KB".format(bytes / 1_000.0)
        else               -> "$bytes B"
    }
}
