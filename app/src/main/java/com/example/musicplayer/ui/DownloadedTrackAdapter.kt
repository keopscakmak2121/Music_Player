package com.example.musicplayer.ui

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.musicplayer.databinding.ItemDownloadedTrackBinding

class DownloadedTrackAdapter(
    private val files: MutableList<LocalFile>,
    private val onPlayClick: (LocalFile) -> Unit,
    private val onDeleteClick: (LocalFile, Int) -> Unit,
    private val onAddToPlaylist: (LocalFile) -> Unit
) : RecyclerView.Adapter<DownloadedTrackAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemDownloadedTrackBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDownloadedTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        holder.binding.apply {
            // Format ikonu + isim
            val typeIcon = if (file.isVideo) "🎬" else "🎵"
            tvFileName.text = "$typeIcon ${file.name}"
            tvFileSize.text = formatSize(file.size)

            root.setOnClickListener { onPlayClick(file) }
            btnPlay.setOnClickListener { onPlayClick(file) }
            btnDelete.setOnClickListener {
                AlertDialog.Builder(holder.itemView.context)
                    .setTitle("Sil")
                    .setMessage("\"${file.name}\" silinsin mi?")
                    .setPositiveButton("Sil") { _, _ -> onDeleteClick(file, holder.adapterPosition) }
                    .setNegativeButton("İptal", null)
                    .show()
            }
            root.setOnLongClickListener {
                onAddToPlaylist(file)
                true
            }
        }
    }

    override fun getItemCount() = files.size

    fun removeAt(position: Int) {
        files.removeAt(position)
        notifyItemRemoved(position)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000     -> "%.0f KB".format(bytes / 1_000.0)
        else               -> "$bytes B"
    }
}
