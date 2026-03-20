package com.example.musicplayer

import android.animation.ObjectAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.example.musicplayer.databinding.ItemTrackBinding
import com.example.musicplayer.model.Track

class TrackAdapter(
    private val tracks: List<Track>,
    private val onTrackClick: (Track) -> Unit,
    private val onDownloadClick: (Track, Int) -> Unit,
    private val onLongClick: ((Track) -> Unit)? = null
) : RecyclerView.Adapter<TrackAdapter.TrackViewHolder>() {

    private val downloadMap = mutableMapOf<Long, Int>()
    private val progressMap = mutableMapOf<Int, Int>() // -2=preparing, -1=done, 0-100=progress
    private var playingPosition: Int = -1

    class TrackViewHolder(val binding: ItemTrackBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val binding = ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TrackViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        val track = tracks[position]
        val progress = progressMap[position]
        val isPlaying = position == playingPosition

        holder.binding.apply {
            tvTrackName.text = track.name
            tvArtistName.text = track.artistName
            tvDuration.text = formatDuration(track.duration)

            // Album art veya play overlay
            if (isPlaying) {
                ivAlbumArt.load(track.image) {
                    transformations(RoundedCornersTransformation(10f))
                    placeholder(android.R.drawable.ic_media_play)
                }
                // Oynatma göstergesi - albumart üzerine play ikonu
                ivAlbumArt.setColorFilter(
                    android.graphics.Color.parseColor("#996C63FF"),
                    android.graphics.PorterDuff.Mode.SRC_ATOP
                )
                // Kart arka planı accent renk
                root.setCardBackgroundColor(android.graphics.Color.parseColor("#1E1A33"))
                // Sol kenarda accent çizgisi
                root.strokeColor = android.graphics.Color.parseColor("#6C63FF")
                root.strokeWidth = 3
                // Şarkı adı accent renk
                tvTrackName.setTextColor(android.graphics.Color.parseColor("#9D97FF"))
                // Pulse animasyonu
                val pulse = ObjectAnimator.ofFloat(ivAlbumArt, "alpha", 1f, 0.6f).apply {
                    duration = 800
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                    interpolator = LinearInterpolator()
                }
                ivAlbumArt.tag = pulse
                pulse.start()
            } else {
                ivAlbumArt.load(track.image) {
                    transformations(RoundedCornersTransformation(10f))
                    placeholder(android.R.drawable.ic_media_play)
                    error(android.R.drawable.ic_media_play)
                }
                ivAlbumArt.clearColorFilter()
                ivAlbumArt.alpha = 1f
                (ivAlbumArt.tag as? ObjectAnimator)?.cancel()
                ivAlbumArt.tag = null
                root.setCardBackgroundColor(android.graphics.Color.parseColor("#1A1A26"))
                root.strokeWidth = 0
                tvTrackName.setTextColor(android.graphics.Color.WHITE)
            }

            // Download durumu
            when {
                progress == null -> {
                    downloadProgress.visibility = View.GONE
                    tvDownloadPercent.visibility = View.GONE
                    btnDownload.setImageResource(android.R.drawable.stat_sys_download)
                    btnDownload.clearColorFilter()
                    btnDownload.animation?.cancel()
                    btnDownload.clearAnimation()
                    btnDownload.isEnabled = true
                }
                progress == -2 -> {
                    downloadProgress.visibility = View.VISIBLE
                    downloadProgress.isIndeterminate = true
                    tvDownloadPercent.visibility = View.VISIBLE
                    tvDownloadPercent.text = "Sunucuda hazırlanıyor…"
                    btnDownload.setImageResource(android.R.drawable.ic_popup_sync)
                    btnDownload.isEnabled = false
                    val rotation = android.view.animation.RotateAnimation(
                        0f, 360f,
                        android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                        android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f
                    ).apply {
                        duration = 1000
                        repeatCount = android.view.animation.Animation.INFINITE
                        interpolator = android.view.animation.LinearInterpolator()
                    }
                    btnDownload.startAnimation(rotation)
                }
                progress == -1 -> {
                    downloadProgress.visibility = View.GONE
                    downloadProgress.isIndeterminate = false
                    tvDownloadPercent.visibility = View.VISIBLE
                    tvDownloadPercent.text = "✓ İndirildi"
                    btnDownload.setImageResource(android.R.drawable.checkbox_on_background)
                    btnDownload.animation?.cancel()
                    btnDownload.clearAnimation()
                    btnDownload.isEnabled = false
                }
                else -> {
                    downloadProgress.visibility = View.VISIBLE
                    downloadProgress.isIndeterminate = false
                    downloadProgress.progress = progress
                    tvDownloadPercent.visibility = View.VISIBLE
                    tvDownloadPercent.text = "%$progress indiriliyor"
                    btnDownload.setImageResource(android.R.drawable.stat_sys_download)
                    btnDownload.animation?.cancel()
                    btnDownload.clearAnimation()
                    btnDownload.isEnabled = false
                }
            }

            root.setOnClickListener { onTrackClick(track) }
            root.setOnLongClickListener { onLongClick?.invoke(track); true }
            btnDownload.setOnClickListener { onDownloadClick(track, position) }
        }
    }

    override fun getItemCount() = tracks.size

    fun setPlayingPosition(position: Int) {
        val old = playingPosition
        playingPosition = position
        if (old >= 0) notifyItemChanged(old)
        if (position >= 0) notifyItemChanged(position)
    }

    fun registerPreparing(position: Int) {
        progressMap[position] = -2
        notifyItemChanged(position)
    }

    fun registerDownload(downloadId: Long, position: Int) {
        downloadMap[downloadId] = position
        progressMap[position] = 0
        notifyItemChanged(position)
    }

    fun updateProgress(downloadId: Long, percent: Int) {
        val position = downloadMap[downloadId] ?: return
        progressMap[position] = percent
        notifyItemChanged(position)
    }

    fun markCompleted(downloadId: Long) {
        val position = downloadMap[downloadId] ?: return
        progressMap[position] = -1
        downloadMap.remove(downloadId)
        notifyItemChanged(position)
    }

    private fun formatDuration(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return "%d:%02d".format(m, s)
    }
}
