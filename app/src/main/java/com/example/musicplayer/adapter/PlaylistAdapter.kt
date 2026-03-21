package com.example.musicplayer.adapter

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.example.musicplayer.databinding.ItemPlaylistBinding
import com.example.musicplayer.db.AppDatabase
import com.example.musicplayer.db.PlaylistEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaylistAdapter(
    private val scope: CoroutineScope,
    private val onClick: (PlaylistEntity) -> Unit,
    private val onDelete: (PlaylistEntity) -> Unit
) : ListAdapter<PlaylistEntity, PlaylistAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<PlaylistEntity>() {
            override fun areItemsTheSame(a: PlaylistEntity, b: PlaylistEntity) = a.id == b.id
            override fun areContentsTheSame(a: PlaylistEntity, b: PlaylistEntity) = a == b
        }
    }

    inner class VH(val binding: ItemPlaylistBinding) : RecyclerView.ViewHolder(binding.root) {
        var job: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemPlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val playlist = getItem(position)
        holder.binding.apply {
            tvPlaylistName.text = playlist.name
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

        // MODERNEŞTİRME: Şarkı sayısı ve İLK ŞARKI RESMİNİ yükle
        holder.job?.cancel()
        holder.job = scope.launch {
            val db = AppDatabase.getInstance(holder.itemView.context)
            db.playlistSongDao().getSongsInPlaylist(playlist.id).collectLatest { songs ->
                withContext(Dispatchers.Main) {
                    holder.binding.tvSongCount.text = "${songs.size} şarkı"
                    
                    // İlk şarkının resmini yükle
                    if (songs.isNotEmpty()) {
                        val firstSong = songs[0]
                        holder.binding.ivPlaylistCover.load(firstSong.thumbnail.ifEmpty { firstSong.videoId }) {
                            crossfade(true)
                            placeholder(android.R.drawable.ic_menu_gallery)
                            error(android.R.drawable.ic_menu_gallery)
                            transformations(RoundedCornersTransformation(14f))
                        }
                    } else {
                        holder.binding.ivPlaylistCover.setImageResource(android.R.drawable.ic_menu_gallery)
                    }
                }
            }
        }
    }

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        holder.job?.cancel()
    }
}
