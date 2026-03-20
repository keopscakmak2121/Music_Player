package com.example.musicplayer.adapter

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.musicplayer.databinding.ItemPlaylistBinding
import com.example.musicplayer.db.PlaylistEntity

class PlaylistAdapter(
    private val onClick: (PlaylistEntity) -> Unit,
    private val onDelete: (PlaylistEntity) -> Unit
) : ListAdapter<PlaylistEntity, PlaylistAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<PlaylistEntity>() {
            override fun areItemsTheSame(a: PlaylistEntity, b: PlaylistEntity) = a.id == b.id
            override fun areContentsTheSame(a: PlaylistEntity, b: PlaylistEntity) = a == b
        }
    }

    inner class VH(val binding: ItemPlaylistBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemPlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val playlist = getItem(position)
        holder.binding.apply {
            tvPlaylistName.text = playlist.name
            tvSongCount.text = "Yükleniyor…"
            root.setOnClickListener { onClick(playlist) }
            btnDelete.setOnClickListener {
                AlertDialog.Builder(holder.itemView.context)
                    .setTitle("Playlist Sil")
                    .setMessage("\"${playlist.name}\" silinsin mi?")
                    .setPositiveButton("Sil") { _, _ -> onDelete(playlist) }
                    .setNegativeButton("İptal", null)
                    .show()
            }
        }
    }

    fun updateSongCount(playlistId: Long, count: Int) {
        val pos = currentList.indexOfFirst { it.id == playlistId }
        if (pos >= 0) notifyItemChanged(pos, count)
    }
}
