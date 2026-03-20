package com.example.musicplayer.ui

import android.app.AlertDialog
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.musicplayer.databinding.FragmentDownloadsBinding
import com.example.musicplayer.db.AppDatabase
import com.example.musicplayer.db.PlaylistSongEntity
import com.example.musicplayer.model.Track
import kotlinx.coroutines.launch
import java.io.File

class DownloadsFragment : Fragment() {

    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!

    var onFileSelected: ((Track, String) -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDownloadsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvDownloads.layoutManager = LinearLayoutManager(requireContext())
        loadDownloadedFiles()
    }

    override fun onResume() {
        super.onResume()
        loadDownloadedFiles()
    }

    private fun loadDownloadedFiles() {
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val files = musicDir?.listFiles { f ->
            f.extension == "mp3" || f.extension == "m4a" || f.extension == "webm"
        }?.sortedByDescending { it.lastModified() }?.toMutableList() ?: mutableListOf()

        updateEmptyState(files.isEmpty())

        binding.rvDownloads.adapter = DownloadedTrackAdapter(
            files,
            onPlayClick = { file ->
                val track = Track(file.name, file.nameWithoutExtension, "Yerel Dosya", "", "", 0)
                onFileSelected?.invoke(track, file.absolutePath)
            },
            onDeleteClick = { file, position ->
                if (file.delete()) {
                    (binding.rvDownloads.adapter as? DownloadedTrackAdapter)?.removeAt(position)
                    Toast.makeText(requireContext(), "Silindi", Toast.LENGTH_SHORT).show()
                    if ((binding.rvDownloads.adapter?.itemCount ?: 0) == 0) updateEmptyState(true)
                } else {
                    Toast.makeText(requireContext(), "Silinemedi", Toast.LENGTH_SHORT).show()
                }
            },
            onAddToPlaylist = { file -> showAddToPlaylistDialog(file) }
        )
    }

    private fun showAddToPlaylistDialog(file: File) {
        val db = AppDatabase.getInstance(requireContext())
        lifecycleScope.launch {
            db.playlistDao().getAllPlaylists().collect { playlists ->
                if (playlists.isEmpty()) {
                    Toast.makeText(requireContext(), "Önce Listeler sekmesinden playlist oluştur", Toast.LENGTH_SHORT).show()
                    return@collect
                }
                val names = playlists.map { it.name }.toTypedArray()
                AlertDialog.Builder(requireContext())
                    .setTitle("\"${file.nameWithoutExtension}\" eklenecek playlist:")
                    .setItems(names) { _, index ->
                        val playlist = playlists[index]
                        lifecycleScope.launch {
                            val already = db.playlistSongDao().isSongInPlaylist(playlist.id, file.name)
                            if (already > 0) {
                                Toast.makeText(requireContext(), "Zaten listede", Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            db.playlistSongDao().insertSong(
                                PlaylistSongEntity(
                                    playlistId = playlist.id,
                                    videoId = file.absolutePath, // yerel dosya yolu
                                    title = file.nameWithoutExtension,
                                    author = "Yerel Dosya",
                                    thumbnail = "",
                                    duration = 0
                                )
                            )
                            Toast.makeText(requireContext(), "\"${playlist.name}\" listesine eklendi", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .show()
            }
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.rvDownloads.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
