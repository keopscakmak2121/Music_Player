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
    private var showingVideo = false  // false=MP3 sekmesi, true=Video sekmesi

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDownloadsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvDownloads.layoutManager = LinearLayoutManager(requireContext())

        // Sekme butonları
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

        // Çalma modu butonları
        binding.btnModeSequential.setOnClickListener { setPlayMode(PlayMode.SEQUENTIAL) }
        binding.btnModeShuffle.setOnClickListener { setPlayMode(PlayMode.SHUFFLE) }
        binding.btnPlayAll.setOnClickListener { playAll() }

        updateTabUI()
        loadDownloadedFiles()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) loadDownloadedFiles()
    }

    override fun onResume() {
        super.onResume()
        loadDownloadedFiles()
    }

    private fun setPlayMode(mode: PlayMode) {
        PlayerManager.playMode = mode
        updatePlayModeUI()
    }

    private fun updatePlayModeUI() {
        val isSeq = PlayerManager.playMode == PlayMode.SEQUENTIAL
        binding.btnModeSequential.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (isSeq) android.graphics.Color.parseColor("#6C63FF") else android.graphics.Color.parseColor("#22223A")
        )
        binding.btnModeShuffle.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (!isSeq) android.graphics.Color.parseColor("#6C63FF") else android.graphics.Color.parseColor("#22223A")
        )
        binding.btnModeShuffle.setTextColor(android.graphics.Color.parseColor(
            if (!isSeq) "#FFFFFF" else "#9999BB"
        ))
    }

    private fun updateTabUI() {
        val accentColor = android.graphics.Color.parseColor("#6C63FF")
        val inactiveColor = android.graphics.Color.parseColor("#22223A")
        binding.btnTabMp3.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (!showingVideo) accentColor else inactiveColor
        )
        binding.btnTabVideo.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (showingVideo) accentColor else inactiveColor
        )
        binding.btnTabMp3.setTextColor(android.graphics.Color.parseColor(
            if (!showingVideo) "#FFFFFF" else "#9999BB"
        ))
        binding.btnTabVideo.setTextColor(android.graphics.Color.parseColor(
            if (showingVideo) "#FFFFFF" else "#9999BB"
        ))
    }

    private fun playAll() {
        val filtered = allFiles.filter { it.isVideo == showingVideo }
        if (filtered.isEmpty()) {
            Toast.makeText(requireContext(), "Çalacak dosya yok", Toast.LENGTH_SHORT).show()
            return
        }
        // Sadece MP3 listesi oynatılabilir (video için external player)
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

    private fun filterAndShow() {
        if (!isAdded || _binding == null) return
        val filtered = allFiles.filter { it.isVideo == showingVideo }
        updateEmptyState(filtered.isEmpty())

        // Çalma modu barı: sadece MP3 sekmesinde göster
        binding.playModeBar.visibility = if (!showingVideo && filtered.isNotEmpty()) View.VISIBLE else View.GONE
        updatePlayModeUI()

        binding.rvDownloads.adapter = DownloadedTrackAdapter(
            filtered.toMutableList(),
            onPlayClick = { file ->
                if (file.isVideo) {
                    openVideoPlayer(file)
                } else {
                    val playUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        file.uri.toString()
                    } else {
                        file.path
                    }
                    val track = Track(file.path, file.name, "Yerel Dosya", "", playUri, 0)
                    onFileSelected?.invoke(track, playUri)
                }
            },
            onDeleteClick = { file, _ -> confirmDelete(file) },
            onAddToPlaylist = { file -> showAddToPlaylistDialog(file) }
        )
    }

    private fun queryMelodifyFiles(): List<LocalFile> {
        val result = mutableListOf<LocalFile>()
        val ctx = requireContext()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // MP3 → Music/Melodify
            val audioProjection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATA
            )
            ctx.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                audioProjection,
                "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("Music/Melodify/%"),
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol   = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                while (cursor.moveToNext()) {
                    val id   = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: continue
                    val size = cursor.getLong(sizeCol)
                    val path = cursor.getString(dataCol) ?: ""
                    val uri  = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    result.add(LocalFile(uri, path, name.substringBeforeLast("."), size, false))
                }
            }

            // MP4 → Önce MediaStore.Video dene (daha güvenilir)
            val videoProjection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATA
            )
            ctx.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                videoProjection,
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("Download/Melodify/%"),
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol   = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                while (cursor.moveToNext()) {
                    val id   = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: continue
                    if (!name.endsWith(".mp4")) continue
                    val size = cursor.getLong(sizeCol)
                    val path = cursor.getString(dataCol) ?: ""
                    val uri  = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    result.add(LocalFile(uri, path, name.substringBeforeLast("."), size, true))
                }
            }

            // Downloads fallback (bazı cihazlarda Video MediaStore'a düşmüyor)
            val dlProjection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.DATA
            )
            ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                dlProjection,
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf("Download/Melodify/%"),
                "${MediaStore.Downloads.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol   = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATA)
                while (cursor.moveToNext()) {
                    val id   = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: continue
                    if (!name.endsWith(".mp4")) continue
                    val size = cursor.getLong(sizeCol)
                    val path = cursor.getString(dataCol) ?: ""
                    val alreadyAdded = result.any { it.name == name.substringBeforeLast(".") && it.isVideo }
                    if (alreadyAdded) continue
                    val uri  = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                    result.add(LocalFile(uri, path, name.substringBeforeLast("."), size, true))
                }
            }

        } else {
            // Android 9-: dosya sistemi üzerinden tara
            listOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ).forEach { dir ->
                dir.walkTopDown()
                    .filter { it.isFile && (it.extension == "mp3" || it.extension == "mp4") }
                    .sortedByDescending { it.lastModified() }
                    .forEach { f ->
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
            Toast.makeText(requireContext(), "Video oynatıcı uygulaması bulunamadı", Toast.LENGTH_SHORT).show()
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
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                requireContext().contentResolver.delete(file.uri, null, null) > 0
                            } else {
                                File(file.path).delete()
                            }
                        } catch (e: Exception) { false }
                    }
                    if (deleted) {
                        AppDatabase.getInstance(requireContext())
                            .playlistSongDao().deleteSongByVideoId(file.path)
                        onFileDeleted?.invoke(file.name)
                        loadDownloadedFiles()
                        Toast.makeText(requireContext(), "Silindi", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), "Silinemedi", Toast.LENGTH_SHORT).show()
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
            if (playlists.isEmpty()) {
                Toast.makeText(requireContext(), "Önce Listeler sekmesinden playlist oluştur", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val names = playlists.map { it.name }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle("\"${file.name}\" eklenecek playlist:")
                .setItems(names) { _, index ->
                    val playlist = playlists[index]
                    lifecycleScope.launch {
                        val already = db.playlistSongDao().isSongInPlaylist(playlist.id, file.path)
                        if (already > 0) {
                            Toast.makeText(requireContext(), "Zaten listede", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
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
                        Toast.makeText(requireContext(), "\"${playlist.name}\" listesine eklendi", Toast.LENGTH_SHORT).show()
                    }
                }.show()
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.rvDownloads.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        if (isEmpty) {
            binding.tvEmptyMessage.text = if (showingVideo) "Henüz indirilen video yok" else "Henüz indirilen müzik yok"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
