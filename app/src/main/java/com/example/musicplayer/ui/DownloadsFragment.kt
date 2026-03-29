package com.example.musicplayer.ui

import android.app.AlertDialog
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.musicplayer.PlayMode
import com.example.musicplayer.PlayerManager
import com.example.musicplayer.R
import com.example.musicplayer.databinding.FragmentDownloadsBinding
import com.example.musicplayer.db.AppDatabase
import com.example.musicplayer.db.PlaylistSongEntity
import com.example.musicplayer.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class LocalFile(
    val uri: Uri,
    val path: String,
    val name: String,
    val size: Long,
    val isVideo: Boolean
)

class DownloadsFragment : Fragment() {

    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!

    var onFileSelected: ((Track, String) -> Unit)? = null
    var onFileDeleted: ((String) -> Unit)? = null

    private var allFiles: List<LocalFile> = emptyList()
    private var showingVideo = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDownloadsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvDownloads.layoutManager = LinearLayoutManager(requireContext())

        binding.btnTabMp3.setOnClickListener {
            showingVideo = false
            updateTabUI()
            filterAndShow()
        }
        binding.btnTabVideo.setOnClickListener {
            showingVideo = true
            updateTabUI()
            filterAndShow()
        }

        binding.btnModeSequential.setOnClickListener { setPlayMode(PlayMode.SEQUENTIAL) }
        binding.btnModeShuffle.setOnClickListener { setPlayMode(PlayMode.SHUFFLE) }
        binding.btnPlayAll.setOnClickListener { playAll() }

        updateTabUI()
        loadDownloadedFiles()
    }

    override fun onResume() {
        super.onResume()
        loadDownloadedFiles()
        updatePlayModeUI()
    }

    private fun setPlayMode(mode: PlayMode) {
        PlayerManager.playMode = mode
        updatePlayModeUI()
    }

    private fun updatePlayModeUI() {
        if (!isAdded) return
        val selectedColor = ContextCompat.getColor(requireContext(), R.color.accent)
        val inactiveColor = ContextCompat.getColor(requireContext(), R.color.bg_elevated)
        val isSeq = PlayerManager.playMode == PlayMode.SEQUENTIAL

        binding.btnModeSequential.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(if (isSeq) selectedColor else inactiveColor)
            text = if (isSeq) "✓ SIRALI" else "SIRALI"
        }
        binding.btnModeShuffle.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(if (!isSeq) selectedColor else inactiveColor)
            text = if (!isSeq) "✓ KARIŞIK" else "🔀 KARIŞIK"
        }
    }

    private fun updateTabUI() {
        if (!isAdded) return
        val accentColor = ContextCompat.getColor(requireContext(), R.color.accent)
        val inactiveColor = ContextCompat.getColor(requireContext(), R.color.bg_elevated)
        binding.btnTabMp3.backgroundTintList = android.content.res.ColorStateList.valueOf(if (!showingVideo) accentColor else inactiveColor)
        binding.btnTabVideo.backgroundTintList = android.content.res.ColorStateList.valueOf(if (showingVideo) accentColor else inactiveColor)
    }

    private fun playAll() {
        val filtered = allFiles.filter { it.isVideo == showingVideo }
        if (filtered.isEmpty()) return
        val tracks = filtered.map { f ->
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) f.uri.toString() else f.path
            Track(uri, f.name, "Yerel Dosya", "", uri, 0)
        }
        PlayerManager.urlResolver = { track, cb -> cb(track.audio) }
        PlayerManager.playQueue(tracks, 0)
    }

    fun loadDownloadedFiles() {
        if (!isAdded || _binding == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            allFiles = withContext(Dispatchers.IO) { queryMelodifyFiles() }
            filterAndShow()
        }
    }

    private var currentAdapter: DownloadedTrackAdapter? = null

    private fun filterAndShow() {
        if (!isAdded || _binding == null) return
        val filtered = allFiles.filter { it.isVideo == showingVideo }
        updateEmptyState(filtered.isEmpty())

        binding.playModeBar.visibility = if (!showingVideo && filtered.isNotEmpty()) View.VISIBLE else View.GONE
        
        val adapter = DownloadedTrackAdapter(
            filtered.toMutableList(),
            onPlayClick = { file ->
                val playUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) file.uri.toString() else file.path
                val track = Track(file.path, file.name, "Yerel Dosya", "", playUri, 0)
                onFileSelected?.invoke(track, playUri)
            },
            onDeleteClick = { file, _ -> confirmDelete(file) },
            onAddToPlaylist = { file -> showAddToPlaylistDialog(file) },
            onSelectionChanged = { count ->
                if (count == -1) {
                    binding.selectionBar.visibility = View.GONE
                } else {
                    binding.selectionBar.visibility = View.VISIBLE
                    binding.tvSelectionCount.text = "$count şarkı seçildi"
                }
            }
        )
        currentAdapter = adapter
        binding.rvDownloads.adapter = adapter

        binding.btnSelectAll.setOnClickListener { currentAdapter?.selectAll() }
        binding.btnCancelSelection.setOnClickListener { currentAdapter?.exitSelectionMode() }
        binding.btnDeleteSelected.setOnClickListener { confirmDeleteSelected() }
        
        binding.btnAddToPlaylistSelected.setOnClickListener {
            val selected = currentAdapter?.getSelectedFiles() ?: return@setOnClickListener
            if (selected.isEmpty()) return@setOnClickListener
            showAddToPlaylistBatchDialog(selected)
        }
    }

    private fun showAddToPlaylistBatchDialog(selectedFiles: List<LocalFile>) {
        val db = AppDatabase.getInstance(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            val playlists = db.playlistDao().getAllPlaylists().first()
            if (playlists.isEmpty()) {
                Toast.makeText(requireContext(), "Önce playlist oluşturun", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val names = playlists.map { it.name }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle("${selectedFiles.size} şarkı eklenecek liste:")
                .setItems(names) { _, index ->
                    val playlist = playlists[index]
                    viewLifecycleOwner.lifecycleScope.launch {
                        selectedFiles.forEach { file ->
                            db.playlistSongDao().insertSong(
                                PlaylistSongEntity(
                                    playlistId = playlist.id,
                                    videoId = file.path,
                                    title = file.name,
                                    author = "Yerel Dosya",
                                    thumbnail = "",
                                    duration = 0
                                )
                            )
                        }
                        Toast.makeText(requireContext(), "Eklendi", Toast.LENGTH_SHORT).show()
                        currentAdapter?.exitSelectionMode()
                    }
                }.show()
        }
    }

    private fun confirmDeleteSelected() {
        val selected = currentAdapter?.getSelectedFiles() ?: return
        if (selected.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle("Toplu Sil")
            .setMessage("${selected.size} dosya silinsin mi?")
            .setPositiveButton("Sil") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        selected.forEach { file ->
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                                    requireContext().contentResolver.delete(file.uri, null, null)
                                else File(file.path).delete()
                                AppDatabase.getInstance(requireContext()).playlistSongDao().deleteSongByVideoId(file.path)
                            } catch (e: Exception) {}
                        }
                    }
                    loadDownloadedFiles()
                }
            }.setNegativeButton("İptal", null).show()
    }

    private fun queryMelodifyFiles(): List<LocalFile> {
        val result = mutableListOf<LocalFile>()
        val ctx = context ?: return emptyList()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collections = listOf(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI
                )
                
                collections.forEach { uri ->
                    val projection = arrayOf(
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.SIZE,
                        MediaStore.MediaColumns.DATA,
                        MediaStore.MediaColumns.RELATIVE_PATH
                    )
                    
                    ctx.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                        val dataCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                        val pathCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                        
                        while (cursor.moveToNext()) {
                            val path = if (pathCol != -1) cursor.getString(pathCol) ?: "" else ""
                            val name = cursor.getString(nameCol) ?: ""
                            
                            if (path.contains("Melodify", ignoreCase = true)) {
                                val id = cursor.getLong(idCol)
                                val size = cursor.getLong(sizeCol)
                                val data = cursor.getString(dataCol) ?: ""
                                val fileUri = ContentUris.withAppendedId(uri, id)
                                val isVideo = name.endsWith(".mp4", ignoreCase = true) || uri == MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                
                                result.add(LocalFile(fileUri, data, name.substringBeforeLast("."), size, isVideo))
                            }
                        }
                    }
                }
            } else {
                listOf(
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Melodify"),
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Melodify")
                ).forEach { dir ->
                    if (dir.exists()) {
                        dir.walkTopDown().filter { it.isFile && (it.extension == "mp3" || it.extension == "mp4") }.forEach { f ->
                            result.add(LocalFile(Uri.fromFile(f), f.absolutePath, f.nameWithoutExtension, f.length(), f.extension == "mp4"))
                        }
                    }
                }
            }
        } catch (e: Exception) {}
        return result.distinctBy { it.path }
    }

    private fun confirmDelete(file: LocalFile) {
        AlertDialog.Builder(requireContext()).setTitle("Sil").setMessage("${file.name} silinsin mi?")
            .setPositiveButton("Sil") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) requireContext().contentResolver.delete(file.uri, null, null) > 0
                            else File(file.path).delete()
                        } catch (e: Exception) { false }
                    }
                    if (ok) {
                        AppDatabase.getInstance(requireContext()).playlistSongDao().deleteSongByVideoId(file.path)
                        loadDownloadedFiles()
                    }
                }
            }.setNegativeButton("İptal", null).show()
    }

    private fun showAddToPlaylistDialog(file: LocalFile) {
        showAddToPlaylistBatchDialog(listOf(file))
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
