package com.example.musicplayer.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.musicplayer.PlayerManager
import com.example.musicplayer.RecentlyPlayedManager
import com.example.musicplayer.TrackAdapter
import com.example.musicplayer.databinding.FragmentRecentlyPlayedBinding
import com.example.musicplayer.model.Track
import com.example.musicplayer.util.FileUtils

class RecentlyPlayedFragment : Fragment() {

    private var _binding: FragmentRecentlyPlayedBinding? = null
    private val binding get() = _binding!!
    private var trackAdapter: TrackAdapter? = null
    private var currentTracks: MutableList<Track> = mutableListOf()

    var onTrackSelected: ((Track, String) -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRecentlyPlayedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        binding.rvRecentlyPlayed.layoutManager = LinearLayoutManager(requireContext())
        
        loadRecentlyPlayed()
        
        binding.btnClearHistory.setOnClickListener {
            RecentlyPlayedManager.clearAll()
            loadRecentlyPlayed()
        }
    }
    
    override fun onResume() {
        super.onResume()
        loadRecentlyPlayed()
    }
    
    private fun loadRecentlyPlayed() {
        currentTracks = RecentlyPlayedManager.getTracks().toMutableList()
        
        if (currentTracks.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE
            binding.rvRecentlyPlayed.visibility = View.GONE
            return
        }
        
        binding.emptyView.visibility = View.GONE
        binding.rvRecentlyPlayed.visibility = View.VISIBLE
        
        trackAdapter = TrackAdapter(
            currentTracks,
            onTrackClick = { },
            onDownloadClick = { track, pos -> },
            onPlayClick = { track, pos ->
                val currentPlayingId = PlayerManager.currentQueue.getOrNull(PlayerManager.currentIndex)?.id
                if (track.id == currentPlayingId) {
                    PlayerManager.togglePlayPause()
                } else {
                    val localUri = FileUtils.findLocalUri(requireContext(), track.name)
                    if (localUri != null) {
                        onTrackSelected?.invoke(track, localUri)
                    } else {
                        onTrackSelected?.invoke(track, track.audio)
                    }
                }
            },
            onAddToPlaylistClick = { },
            onLongClick = { },
            onCancelDownload = { }
        )
        
        binding.rvRecentlyPlayed.adapter = trackAdapter
    }
    
    fun updatePlayingPosition(index: Int) {
        trackAdapter?.setPlayingPosition(index)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}