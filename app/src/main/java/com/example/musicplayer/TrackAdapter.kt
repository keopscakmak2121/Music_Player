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

// Payload türleri — tam rebind yerine sadece ilgili view güncellenir
private const val PAYLOAD_PROGRESS = "progress"
private const val PAYLOAD_PLAYING = "playing"

class TrackAdapter(
    private val tracks: List<Track>,
    private val onTrackClick: (Track) -> Unit,
    private val onDownloadClick: (Track, Int) -> Unit,
    private val onLongClick: ((Track) -> Unit)? = null,
    private val onCancelDownload: ((Long) -> Unit)? = null   // iptal callback'i
) : RecyclerView.Adapter<TrackAdapter.TrackViewHolder>() {

    private val downloadMap = mutableMapOf<Long, Int>()
    private val progressMap = mutableMapOf<Int, Int>() // -2=preparing, -1=done, 0-100=progress
    private var playingPosition: Int = -1

    /** Pozisyon → downloadId ters haritası (iptal için) */
    private fun downloadIdForPosition(position: Int): Long? =
        downloadMap.entries.firstOrNull { it.value == position }?.key

    class TrackViewHolder(val binding: ItemTrackBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val binding = ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TrackViewHolder(binding)
    }

    // Payload varsa sadece ilgili kısmı güncelle, yoksa tam bind
    override fun onBindViewHolder(holder: TrackViewHolder, position: Int, payloads: List<Any>) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }
        payloads.forEach { payload ->
            when (payload) {
                PAYLOAD_PROGRESS -> bindDownloadState(holder.binding, position)
                PAYLOAD_PLAYING  -> bindPlayingState(holder.binding, position, tracks[position])
            }
        }
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        val track = tracks[position]
        holder.binding.apply {
            tvTrackName.text = track.name
            tvArtistName.text = track.artistName
            tvDuration.text = formatDuration(track.duration)
            root.setOnClickListener { onTrackClick(track) }
            root.setOnLongClickListener { onLongClick?.invoke(track); true }

            btnDownload.setOnClickListener {
                val dlId = downloadIdForPosition(position)
                val isDownloading = dlId != null && progressMap[position] != null && progressMap[position] != -1
                if (isDownloading) {
                    // İndirme devam ediyor → iptal dialog'u
                    android.app.AlertDialog.Builder(holder.itemView.context)
                        .setTitle("İndirmeyi İptal Et")
                        .setMessage("\"${track.name}\" indirmesi iptal edilsin mi?")
                        .setPositiveButton("İptal Et") { _, _ -> onCancelDownload?.invoke(dlId!!) }
                        .setNegativeButton("Devam Et", null)
                        .show()
                } else {
                    onDownloadClick(track, position)
                }
            }
            // Uzun basış hala iptal eder (ek güvenlik)
            btnDownload.setOnLongClickListener {
                val dlId = downloadIdForPosition(position)
                if (dlId != null && progressMap[position] != null && progressMap[position] != -1) {
                    onCancelDownload?.invoke(dlId)
                    true
                } else false
            }
        }
        bindPlayingState(holder.binding, position, track)
        bindDownloadState(holder.binding, position)
    }

    private fun bindPlayingState(binding: ItemTrackBinding, position: Int, track: Track) {
        val isPlaying = position == playingPosition
        binding.apply {
            if (isPlaying) {
                ivAlbumArt.load(track.image) {
                    transformations(RoundedCornersTransformation(10f))
                    placeholder(android.R.drawable.ic_media_play)
                }
                ivAlbumArt.setColorFilter(
                    android.graphics.Color.parseColor("#996C63FF"),
                    android.graphics.PorterDuff.Mode.SRC_ATOP
                )
                root.setCardBackgroundColor(android.graphics.Color.parseColor("#1E1A33"))
                root.strokeColor = android.graphics.Color.parseColor("#6C63FF")
                root.strokeWidth = 3
                tvTrackName.setTextColor(android.graphics.Color.parseColor("#9D97FF"))
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
        }
    }

    private fun bindDownloadState(binding: ItemTrackBinding, position: Int) {
        val progress = progressMap[position]
        binding.apply {
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
        }
    }

    override fun getItemCount() = tracks.size

    fun setPlayingPosition(position: Int) {
        val old = playingPosition
        playingPosition = position
        if (old >= 0) notifyItemChanged(old, PAYLOAD_PLAYING)
        if (position >= 0) notifyItemChanged(position, PAYLOAD_PLAYING)
    }

    fun registerPreparing(position: Int) {
        progressMap[position] = -2
        notifyItemChanged(position, PAYLOAD_PROGRESS)
    }

    fun registerDownload(downloadId: Long, position: Int) {
        downloadMap[downloadId] = position
        progressMap[position] = 0
        notifyItemChanged(position, PAYLOAD_PROGRESS)
    }

    fun updateProgress(downloadId: Long, percent: Int) {
        val position = downloadMap[downloadId] ?: return
        progressMap[position] = percent
        notifyItemChanged(position, PAYLOAD_PROGRESS)
    }

    fun markCompleted(downloadId: Long) {
        val position = downloadMap[downloadId] ?: return
        progressMap[position] = -1
        downloadMap.remove(downloadId)
        notifyItemChanged(position, PAYLOAD_PROGRESS)
    }

    /** MediaStore sync için — downloadId olmadan pozisyon ile tamamlandı işaretle */
    fun markCompletedByPosition(position: Int) {
        if (progressMap[position] == -1) return  // zaten işaretli
        progressMap[position] = -1
        notifyItemChanged(position, PAYLOAD_PROGRESS)
    }

    /** İptal için fakeId → position */
    fun positionForFakeId(fakeId: Long): Int? = downloadMap[fakeId]

    /** İndirme hata aldı — butonu sıfırla, tekrar tıklanabilir yap */
    fun cancelDownload(position: Int) {
        progressMap.remove(position)
        // downloadMap'ten de temizle
        downloadMap.entries.removeIf { it.value == position }
        notifyItemChanged(position, PAYLOAD_PROGRESS)
    }

    private fun formatDuration(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return "%d:%02d".format(m, s)
    }
}
