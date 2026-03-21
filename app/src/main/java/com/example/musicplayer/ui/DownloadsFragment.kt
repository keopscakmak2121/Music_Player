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
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.musicplayer.PlayMode
import com.example.musicplayer.PlayerManager
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
        updatePlayModeUI() // Sayfa açıldığında modu göster
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            loadDownloadedFiles()
            updatePlayModeUI()
        }
    }

    private fun setPlayMode(mode: PlayMode) {
        PlayerManager.playMode = mode
        updatePlayModeUI()
        val msg = if (mode == PlayMode.SEQUENTIAL) "Sıralı çalma aktif" else "Karışık çalma aktif"
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    private fun updatePlayModeUI() {
        val selectedColor = android.graphics.Color.parseColor("#7C6FFF") // Parlak Mor (Aktif)
        val inactiveColor = android.graphics.Color.parseColor("#22223A") // Koyu Gri (Pasif)
        val white = android.graphics.Color.parseColor("#FFFFFF")
        val gray = android.graphics.Color.parseColor("#9999BB")

        val isSeq = PlayerManager.playMode == PlayMode.SEQUENTIAL

        // Sıralı Butonu
        binding.btnModeSequential.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(if (isSeq) selectedColor else inactiveColor)
            setTextColor(if (isSeq) white else gray)
            text = if (isSeq) "✓ SIRALI" else "SIRALI"
        }

        // Karışık Butonu
        binding.btnModeShuffle.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(if (!isSeq) selectedColor else inactiveColor)
            setTextColor(if (!isSeq) white else gray)
            text = if (!isSeq) "✓ KARIŞIK" else "🔀 KARIŞIK"
        }
    }

    private fun updateTabUI() {
        val accentColor = android.graphics.Color.parseColor("#7C6FFF")
        val inactiveColor = android.graphics.Color.parseColor("#22223A")
        binding.btnTabMp3.backgroundTintList = android.content.res.ColorStateList.valueOf(if (!showingVideo) accentColor else inactiveColor)
        binding.btnTabVideo.backgroundTintList = android.content.res.ColorStateList.valueOf(if (showingVideo) accentColor else inactiveColor)
        binding.btnTabMp3.setTextColor(if (!showingVideo) android.graphics.Color.WHITE else android.graphics.Color.parseColor("#9999BB"))
        binding.btnTabVideo.setTextColor(if (showingVideo) android.graphics.Color.WHITE else android.graphics.Color.parseColor("#9999BB"))
    }

    private fun playAll() {
        val filtered = allFiles.filter { it.isVideo == showingVideo }
        if (filtered.isEmpty()) {
            Toast.makeText(requireContext(), "Çalacak dosya yok", Toast.LENGTH_SHORT).show()
            return
        }
        if (showingVideo) {
            Toast.makeText(requireContext(), "Videolar tek tek açılır", Toast.LENGTH_SHORT).show()
            return
        }
        val tracks = filtered.map { f ->
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) f.uri.toString() else f.path
            Track(uri, f.name, "Yerel Dosya", "", uri, 0)
        }
        PlayerManager.urlResolver = { track, cb -> cb(track.audio) }
        PlayerManager.playQueue(tracks, 0)
        Toast.makeText(requireContext(), "${tracks.size} şarkı çalınıyor", Toast.LENGTH_SHORT).show()
    }

    fun loadDownloadedFiles() {
        if (!isAdded || _binding == null) return
        lifecycleScope.launch {
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
        updatePlayModeUI()

        val adapter = DownloadedTrackAdapter(
            filtered.toMutableList(),
            onPlayClick = { file ->
                if (file.isVideo) {
                    openVideoPlayer(file)
                } else {
                    val playUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        file.uri.toString() else file.path
                    val track = Track(file.path, file.name, "Yerel Dosya", "", playUri, 0)
                    onFileSelected?.invoke(track, playUri)
                }
            },
            onDeleteClick = { file, _ -> confirmDelete(file) },
            onAddToPlaylist = { file -> showAddToPlaylistDialog(file) },
            onSelectionChanged = { count ->
                if (count == -1) {
                    binding.selectionBar.visibility = View.GONE
                    binding.btnTabMp3.isEnabled = true
                    binding.btnTabVideo.isEnabled = true
                } else {
                    binding.selectionBar.visibility = View.VISIBLE
                    binding.tvSelectionCount.text = "$count şarkı seçildi"
                    binding.btnTabMp3.isEnabled = false
                    binding.btnTabVideo.isEnabled = false
                }
            }
        )
        currentAdapter = adapter
        binding.rvDownloads.adapter = adapter

        binding.btnSelectAll.setOnClickListener { currentAdapter?.selectAll() }
        binding.btnDeleteSelected.setOnClickListener { confirmDeleteSelected() }
        binding.btnCancelSelection.setOnClickListener { currentAdapter?.exitSelectionMode() }
    }

    private fun confirmDeleteSelected() {
        val selected = currentAdapter?.getSelectedFiles() ?: return
        if (selected.isEmpty()) return
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Toplu Sil")
            .setMessage("${selected.size} dosya silinsin mi?")
            .setPositiveButton("Sil") { _, _ ->
                lifecycleScope.launch {
                    var deletedCount = 0
                    withContext(Dispatchers.IO) {
                        selected.forEach { file ->
                            val ok = try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                                    requireContext().contentResolver.delete(file.uri, null, null) > 0
                                else java.io.File(file.path).delete()
                            } catch (e: Exception) { false }
                            if (ok) {
                                deletedCount++
                                AppDatabase.getInstance(requireContext())
                                    .playlistSongDao().deleteSongByVideoId(file.path)
                                onFileDeleted?.invoke(file.name)
                            }
                        }
                    }
                    Toast.makeText(requireContext(), "$deletedCount dosya silindi", Toast.LENGTH_SHORT).show()
                    loadDownloadedFiles()
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun queryMelodifyFiles(): List<LocalFile> {
        val result = mutableListOf<LocalFile>()
        val ctx = requireContext()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val audioProjection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATA)
            ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, audioProjection, "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?", arrayOf("Music/Melodify/%"), "${MediaStore.Audio.Media.DATE_ADDED} DESC")?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: continue
                    val size = cursor.getLong(sizeCol)
                    val path = cursor.getString(dataCol) ?: ""
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    result.add(LocalFile(uri, path, name.substringBeforeLast("."), size, false))
                }
            }

            val videoProjection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATA)
            ctx.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoProjection, "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?", arrayOf("Download/Melodify/%"), "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: continue
                    if (!name.endsWith(".mp4")) continue
                    val size = cursor.getLong(sizeCol)
                    val path = cursor.getString(dataCol) ?: ""
                    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    result.add(LocalFile(uri, path, name.substringBeforeLast("."), size, true))
                }
            }
        } else {
            listOf(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)).forEach { dir ->
                dir.walkTopDown().filter { it.isFile && (it.extension == "mp3" || it.extension == "mp4") }.sortedByDescending { it.lastModified() }.forEach { f ->
                    val isVideo = f.extension == "mp4"
                    result.add(LocalFile(Uri.fromFile(f), f.absolutePath, f.nameWithoutExtension, f.length(), isVideo))
                }
            }
        }
        return result
    }

    private fun openVideoPlayer(file: LocalFile) {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(file.uri, "video/mp4")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(intent, "Video aç"))
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(requireContext(), "Video oynatıcı bulunamadı", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(file: LocalFile) {
        AlertDialog.Builder(requireContext())
            .setTitle("Dosyayı Sil")
            .setMessage("\"${file.name}\" silinsin mi?")
            .setPositiveButton("Sil") { _, _ ->
                lifecycleScope.launch {
                    val deleted = withContext(Dispatchers.IO) {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) requireContext().contentResolver.delete(file.uri, null, null) > 0
                            else File(file.path).delete()
                        } catch (e: Exception) { false }
                    }
                    if (deleted) {
                        AppDatabase.getInstance(requireContext()).playlistSongDao().deleteSongByVideoId(file.path)
                        onFileDeleted?.invoke(file.name)
                        loadDownloadedFiles()
                        Toast.makeText(requireContext(), "Silindi", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun showAddToPlaylistDialog(file: LocalFile) {
        val db = AppDatabase.getInstance(requireContext())
        lifecycleScope.launch {
            val playlists = db.playlistDao().getAllPlaylists().first()
            if (playlists.isEmpty()) return@launch
            val names = playlists.map { it.name }.toTypedArray()
            AlertDialog.Builder(requireContext()).setTitle("Playlist Seçin").setItems(names) { _, index ->
                val playlist = playlists[index]
                lifecycleScope.launch {
                    db.playlistSongDao().insertSong(PlaylistSongEntity(playlistId = playlist.id, videoId = file.path, title = file.name, author = "Yerel Dosya", thumbnail = "", duration = 0))
                    Toast.makeText(requireContext(), "Eklendi", Toast.LENGTH_SHORT).show()
                }
            }.show()
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
