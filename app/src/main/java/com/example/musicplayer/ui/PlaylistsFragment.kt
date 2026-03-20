package com.example.musicplayer.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.musicplayer.adapter.PlaylistAdapter
import com.example.musicplayer.databinding.FragmentPlaylistsBinding
import com.example.musicplayer.db.AppDatabase
import com.example.musicplayer.db.PlaylistEntity
import kotlinx.coroutines.launch

class PlaylistsFragment : Fragment() {

    private var _binding: FragmentPlaylistsBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: PlaylistAdapter

    var onPlaylistClick: ((PlaylistEntity) -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPlaylistsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val db = AppDatabase.getInstance(requireContext())

        adapter = PlaylistAdapter(
            onClick = { playlist -> onPlaylistClick?.invoke(playlist) },
            onDelete = { playlist ->
                lifecycleScope.launch {
                    db.playlistDao().deletePlaylistSongs(playlist.id)
                    db.playlistDao().deletePlaylist(playlist)
                    Toast.makeText(requireContext(), "Silindi", Toast.LENGTH_SHORT).show()
                }
            }
        )

        binding.rvPlaylists.layoutManager = LinearLayoutManager(requireContext())
        binding.rvPlaylists.adapter = adapter

        // Observe playlists
        lifecycleScope.launch {
            db.playlistDao().getAllPlaylists().collect { playlists ->
                adapter.submitList(playlists)
                binding.emptyView.visibility = if (playlists.isEmpty()) View.VISIBLE else View.GONE
                binding.rvPlaylists.visibility = if (playlists.isEmpty()) View.GONE else View.VISIBLE

                // Update song counts
                playlists.forEach { playlist ->
                    lifecycleScope.launch {
                        db.playlistSongDao().getSongsInPlaylist(playlist.id).collect { songs ->
                            val pos = adapter.currentList.indexOfFirst { it.id == playlist.id }
                            if (pos >= 0) {
                                binding.rvPlaylists.post {
                                    val vh = binding.rvPlaylists.findViewHolderForAdapterPosition(pos)
                                    val binding2 = (vh as? com.example.musicplayer.adapter.PlaylistAdapter.VH)?.binding
                                    binding2?.tvSongCount?.text = "${songs.size} şarkı"
                                }
                            }
                        }
                    }
                }
            }
        }

        binding.fabNewPlaylist.setOnClickListener { showCreateDialog(db) }
    }

    private fun showCreateDialog(db: AppDatabase) {
        val input = EditText(requireContext()).apply {
            hint = "Playlist adı"
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Yeni Playlist")
            .setView(input)
            .setPositiveButton("Oluştur") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch {
                        db.playlistDao().insertPlaylist(PlaylistEntity(name = name))
                        Toast.makeText(requireContext(), "\"$name\" oluşturuldu", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
