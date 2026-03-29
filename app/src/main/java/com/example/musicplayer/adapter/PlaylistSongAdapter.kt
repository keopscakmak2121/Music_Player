package com.example.musicplayer.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.musicplayer.R
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
        val context = holder.itemView.context

        holder.binding.apply {
            tvTrackName.text = song.title
            tvArtistName.text = song.author
            tvDuration.text = formatDuration(song.duration)
            
            btnPlay.visibility = View.GONE
            downloadProgress.visibility = View.GONE
            tvDownloadPercent.visibility = View.GONE

            ivAlbumArt.setImageResource(android.R.drawable.ic_media_play)
            ivAlbumArt.setColorFilter(ContextCompat.getColor(context, R.color.accent))
            ivAlbumArt.setPadding(8, 8, 8, 8)

            if (isActuallyPlaying) {
                root.strokeWidth = 2
                root.strokeColor = ContextCompat.getColor(context, R.color.accent)
            } else {
                root.strokeWidth = if (isThisTrack) 2 else 0
                root.strokeColor = if (isThisTrack) ContextCompat.getColor(context, R.color.text_hint) else 0
            }

            root.setOnClickListener { onClick(song, position) }
            btnDownload.setImageResource(android.R.drawable.ic_menu_delete)
            btnDownload.setColorFilter(ContextCompat.getColor(context, R.color.error))
            btnDownload.setOnClickListener {
                android.app.AlertDialog.Builder(context)
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
