package com.example.musicplayer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.musicplayer.databinding.ItemTrackBinding
import com.example.musicplayer.model.Track

class TrackAdapter(
    private val tracks: List<Track>,
    private val onTrackClick: (Track) -> Unit,
    private val onDownloadClick: (Track) -> Unit
) : RecyclerView.Adapter<TrackAdapter.TrackViewHolder>() {

    class TrackViewHolder(val binding: ItemTrackBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val binding = ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TrackViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        val track = tracks[position]
        holder.binding.apply {
            tvTrackName.text = track.name
            tvArtistName.text = track.artistName
            ivAlbumArt.load(track.image)
            
            root.setOnClickListener { onTrackClick(track) }
            btnDownload.setOnClickListener { onDownloadClick(track) }
        }
    }

    override fun getItemCount() = tracks.size
}
