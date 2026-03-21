package com.example.musicplayer.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.example.musicplayer.databinding.ItemTrackBinding
import com.example.musicplayer.db.PlaylistSongEntity
import com.example.musicplayer.PlayerManager

class PlaylistSongAdapter(
    private val onClick: (PlaylistSongEntity, Int) -> Unit,
    private val onRemove: (PlaylistSongEntity) -> Unit
) : ListAdapter<PlaylistSongEntity, PlaylistSongAdapter.VH>(DIFF) {

    private var playingPosition: Int = -1

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<PlaylistSongEntity>() {
            override fun areItemsTheSame(a: PlaylistSongEntity, b: PlaylistSongEntity) = a.id == b.id
            override fun areContentsTheSame(a: PlaylistSongEntity, b: PlaylistSongEntity) = a == b
        }
    }

    fun setPlayingPosition(position: Int) {
        val old = playingPosition; playingPosition = position
        if (old >= 0) notifyItemChanged(old)
        if (position >= 0) notifyItemChanged(position)
    }

    inner class VH(val binding: ItemTrackBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val song = getItem(position)
        val isThisTrack = position == playingPosition
        val isActuallyPlaying = isThisTrack && PlayerManager.isPlaying()

        holder.binding.apply {
            tvTrackName.text = song.title
            tvArtistName.text = song.author
            tvDuration.text = formatDuration(song.duration)
            
            btnPlay.visibility = View.GONE
            downloadProgress.visibility = View.GONE
            tvDownloadPercent.visibility = View.GONE

            if (song.thumbnail.isNotEmpty()) {
                ivAlbumArt.load(song.thumbnail) {
                    transformations(RoundedCornersTransformation(10f))
                }
            } else {
                // Sessiz mod yerine Play ikonu (Daha mantıklı)
                ivAlbumArt.setImageResource(android.R.drawable.ic_media_play)
                ivAlbumArt.setColorFilter(android.graphics.Color.parseColor("#7C6FFF"))
            }

            if (isActuallyPlaying) {
                root.strokeWidth = 2
                root.strokeColor = android.graphics.Color.parseColor("#7C6FFF")
            } else {
                root.strokeWidth = if (isThisTrack) 2 else 0
                root.strokeColor = if (isThisTrack) android.graphics.Color.parseColor("#444466") else 0
            }

            root.setOnClickListener { onClick(song, position) }
            btnDownload.setImageResource(android.R.drawable.ic_menu_delete)
            btnDownload.setColorFilter(android.graphics.Color.parseColor("#FF5555"))
            btnDownload.setOnClickListener {
                android.app.AlertDialog.Builder(holder.itemView.context)
                    .setTitle("Kaldır")
                    .setMessage("\"${song.title}\" listeden kaldırılsın mı?")
                    .setPositiveButton("Kaldır") { _, _ -> onRemove(song) }
                    .setNegativeButton("İptal", null)
                    .show()
            }
        }
    }

    private fun formatDuration(seconds: Int): String {
        val m = seconds / 60; val s = seconds % 60
        return "%d:%02d".format(m, s)
    }
}
