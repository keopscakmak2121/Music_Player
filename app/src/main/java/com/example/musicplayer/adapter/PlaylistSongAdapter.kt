package com.example.musicplayer.adapter

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.example.musicplayer.databinding.ItemTrackBinding
import com.example.musicplayer.db.PlaylistSongEntity

class PlaylistSongAdapter(
    private val onClick: (PlaylistSongEntity, Int) -> Unit,
    private val onRemove: (PlaylistSongEntity) -> Unit
) : ListAdapter<PlaylistSongEntity, PlaylistSongAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<PlaylistSongEntity>() {
            override fun areItemsTheSame(a: PlaylistSongEntity, b: PlaylistSongEntity) = a.id == b.id
            override fun areContentsTheSame(a: PlaylistSongEntity, b: PlaylistSongEntity) = a == b
        }
    }

    inner class VH(val binding: ItemTrackBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val song = getItem(position)
        holder.binding.apply {
            tvTrackName.text = song.title
            tvArtistName.text = song.author
            tvDuration.text = formatDuration(song.duration)
            downloadProgress.visibility = android.view.View.GONE
            tvDownloadPercent.visibility = android.view.View.GONE
            ivAlbumArt.load(song.thumbnail) {
                transformations(RoundedCornersTransformation(10f))
                placeholder(android.R.drawable.ic_media_play)
            }
            root.setOnClickListener { onClick(song, position) }
            // Download button repurposed as remove button
            btnDownload.setImageResource(android.R.drawable.ic_menu_delete)
            btnDownload.setColorFilter(android.graphics.Color.parseColor("#FF5555"))
            btnDownload.isEnabled = true
            btnDownload.setOnClickListener {
                AlertDialog.Builder(holder.itemView.context)
                    .setTitle("Kaldır")
                    .setMessage("\"${song.title}\" listeden kaldırılsın mı?")
                    .setPositiveButton("Kaldır") { _, _ -> onRemove(song) }
                    .setNegativeButton("İptal", null)
                    .show()
            }
        }
    }

    private fun formatDuration(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return "%d:%02d".format(m, s)
    }
}
