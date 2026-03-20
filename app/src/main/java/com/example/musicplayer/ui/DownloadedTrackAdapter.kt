package com.example.musicplayer.ui

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.example.musicplayer.databinding.ItemDownloadedTrackBinding
import java.io.File

class DownloadedTrackAdapter(
    private val files: MutableList<File>,
    private val onPlayClick: (File) -> Unit,
    private val onDeleteClick: (File, Int) -> Unit,
    private val onAddToPlaylist: (File) -> Unit
) : RecyclerView.Adapter<DownloadedTrackAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemDownloadedTrackBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDownloadedTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        holder.binding.apply {
            tvFileName.text = file.nameWithoutExtension
            tvFileSize.text = formatSize(file.length())
            root.setOnClickListener { onPlayClick(file) }
            btnPlay.setOnClickListener { onPlayClick(file) }
            btnDelete.setOnClickListener {
                AlertDialog.Builder(holder.itemView.context)
                    .setTitle("Sil")
                    .setMessage("\"${file.nameWithoutExtension}\" silinsin mi?")
                    .setPositiveButton("Sil") { _, _ -> onDeleteClick(file, holder.adapterPosition) }
                    .setNegativeButton("İptal", null)
                    .show()
            }
            // Uzun bas → playlist'e ekle
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

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
            else -> "$bytes B"
        }
    }
}
