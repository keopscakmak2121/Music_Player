package com.example.musicplayer.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil.load
import coil.transform.RoundedCornersTransformation
import com.example.musicplayer.PlayMode
import com.example.musicplayer.PlayerManager
import com.example.musicplayer.R
import com.example.musicplayer.databinding.FragmentFullPlayerBinding
import com.example.musicplayer.model.Track
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class FullPlayerFragment : Fragment() {

    private var _binding: FragmentFullPlayerBinding? = null
    private val binding get() = _binding!!

    private var updateJob: Job? = null
    private var isUserSeeking = false

    var onBack: (() -> Unit)? = null
    var onDownload: ((Track) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFullPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupListeners()
        observePlayer()
        startProgressUpdate()
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            onBack?.invoke()
        }

        binding.btnFullDownload.setOnClickListener {
            val (track, _) = PlayerManager.currentTrackFlow.value
            if (track != null) {
                if (track.audio.isNotEmpty() && !track.audio.startsWith("http")) {
                    Toast.makeText(context, "Bu şarkı zaten indirildi", Toast.LENGTH_SHORT).show()
                } else {
                    onDownload?.invoke(track)
                }
            }
        }

        binding.btnPlayPause.setOnClickListener {
            PlayerManager.togglePlayPause()
        }

        binding.btnPrev.setOnClickListener {
            PlayerManager.playPrev()
        }

        binding.btnNext.setOnClickListener {
            PlayerManager.playNext()
        }

        binding.btnShuffle.setOnClickListener {
            PlayerManager.playMode = if (PlayerManager.playMode == PlayMode.SHUFFLE) {
                PlayMode.SEQUENTIAL
            } else {
                PlayMode.SHUFFLE
            }
            updateShuffleRepeatUI()
        }

        binding.btnRepeat.setOnClickListener {
            PlayerManager.repeatMode = !PlayerManager.repeatMode
            updateShuffleRepeatUI()
        }

        binding.fullPlayerSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = PlayerManager.getDuration()
                    if (duration > 0) {
                        val newPos = (progress.toLong() * duration) / 1000
                        binding.tvCurrentTime.text = formatTime(newPos)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val duration = PlayerManager.getDuration()
                if (duration > 0) {
                    val newPos = (binding.fullPlayerSeekBar.progress.toLong() * duration) / 1000
                    PlayerManager.seekTo(newPos)
                }
                isUserSeeking = false
            }
        })
    }

    private fun observePlayer() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    PlayerManager.currentTrackFlow.collect { (track, _) ->
                        track?.let {
                            binding.tvFullTitle.text = it.name
                            binding.tvFullArtist.text = it.artistName
                            binding.ivFullArt.load(it.image) {
                                crossfade(true)
                                transformations(RoundedCornersTransformation(24f))
                            }
                            
                            // İndirme butonu durumunu güncelle
                            if (it.audio.isNotEmpty() && !it.audio.startsWith("http")) {
                                binding.btnFullDownload.setImageResource(android.R.drawable.stat_sys_download_done)
                                binding.btnFullDownload.alpha = 0.5f
                            } else {
                                binding.btnFullDownload.setImageResource(android.R.drawable.stat_sys_download)
                                binding.btnFullDownload.alpha = 1.0f
                            }
                        }
                    }
                }
                launch {
                    PlayerManager.isPlayingFlow.collect { isPlaying ->
                        binding.btnPlayPause.setImageResource(
                            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
                        )
                    }
                }
            }
        }
        
        updateShuffleRepeatUI()
    }

    private fun updateShuffleRepeatUI() {
        val context = context ?: return
        val activeColor = ContextCompat.getColor(context, R.color.accent)
        val inactiveColor = Color.parseColor("#8A84BB")

        binding.btnShuffle.imageTintList = ColorStateList.valueOf(
            if (PlayerManager.playMode == PlayMode.SHUFFLE) activeColor else inactiveColor
        )
        
        binding.btnRepeat.imageTintList = ColorStateList.valueOf(
            if (PlayerManager.repeatMode) activeColor else inactiveColor
        )
    }

    private fun startProgressUpdate() {
        updateJob?.cancel()
        updateJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    if (!isUserSeeking) {
                        val duration = PlayerManager.getDuration()
                        val position = PlayerManager.getCurrentPosition()
                        if (duration > 0) {
                            binding.fullPlayerSeekBar.progress = ((position * 1000) / duration).toInt()
                            binding.tvCurrentTime.text = formatTime(position)
                            binding.tvTotalTime.text = formatTime(duration)
                        }
                    }
                    delay(1000)
                }
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        updateJob?.cancel()
        _binding = null
    }
}
