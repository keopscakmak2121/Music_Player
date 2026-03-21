package com.example.musicplayer.ui

import android.app.AlertDialog
import android.graphics.PorterDuff
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
            tvFileName.text = file.name
            tvFileSize.text = formatSize(file.size)

            // Video için mor-pembe, MP3 için mor-mavi ikon tonu
            val iconTint = if (file.isVideo)
                android.graphics.Color.parseColor("#C961FF")
            else
                android.graphics.Color.parseColor("#7C6FFF")
            ivMusicIcon.setColorFilter(iconTint, android.graphics.PorterDuff.Mode.SRC_IN)

            // Video için film-kuşağı simgesi, MP3 için play simgesi
            ivMusicIcon.setImageResource(
                if (file.isVideo) android.R.drawable.ic_media_ff
                else android.R.drawable.ic_media_play
            )

            // Video kartı kenarlığını farklı renkte göster
            root.strokeColor = if (file.isVideo)
                android.graphics.Color.parseColor("#3D2455")
            else
                android.graphics.Color.parseColor("#2A2A45")

            root.setOnClickListener { onPlayClick(file) }
            btnPlay.setOnClickListener { onPlayClick(file) }

            // Video için oynat ikonu da farklı renk
            btnPlay.setColorFilter(iconTint, android.graphics.PorterDuff.Mode.SRC_IN)

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
