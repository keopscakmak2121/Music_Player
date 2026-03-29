package com.example.musicplayer

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.load
import coil.transform.RoundedCornersTransformation
import com.example.musicplayer.databinding.ActivityMainBinding
import com.example.musicplayer.db.PlaylistEntity
import com.example.musicplayer.model.Track
import com.example.musicplayer.ui.DiscoverFragment
import com.example.musicplayer.ui.DownloadsFragment
import com.example.musicplayer.ui.PlaylistDetailFragment
import com.example.musicplayer.ui.PlaylistsFragment
import com.example.musicplayer.ui.SettingsFragment
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController?
        get() = if (controllerFuture?.isDone == true) controllerFuture?.get() else null

    private lateinit var discoverFragment: DiscoverFragment
    private lateinit var playlistsFragment: PlaylistsFragment
    private lateinit var downloadsFragment: DownloadsFragment
    private lateinit var settingsFragment: SettingsFragment
    private lateinit var playlistDetailFragment: PlaylistDetailFragment

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isUserSeeking = false

    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            if (!isUserSeeking && PlayerManager.isPlaying()) {
                val duration = PlayerManager.getDuration()
                if (duration > 0) {
                    val pos = PlayerManager.getCurrentPosition()
                    binding.miniPlayerSeekBar.progress = ((pos * 1000) / duration).toInt()
                }
            }
            mainHandler.postDelayed(this, 1000)
        }
    }

    private val playbackStateListener: (Boolean) -> Unit = { isPlaying ->
        runOnUiThread {
            binding.miniPlayerPlayPause.setImageResource(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val postNotifGranted = permissions[Manifest.permission.POST_NOTIFICATIONS] ?: true
        val writeStorageGranted = permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] ?: true
        
        if (!postNotifGranted) {
            Toast.makeText(this, "Bildirim izni verilmedi.", Toast.LENGTH_SHORT).show()
        }
        if (!writeStorageGranted) {
            Toast.makeText(this, "Depolama izni verilmedi. İndirmeler çalışmayabilir.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkPermissions()
        setupFragments(savedInstanceState)
        setupMediaController()
        setupBottomNav()
        setupMiniPlayer()
        wireCallbacks()
        
        mainHandler.post(updateProgressRunnable)
    }

    private fun checkPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun setupFragments(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            discoverFragment = DiscoverFragment()
            playlistsFragment = PlaylistsFragment()
            downloadsFragment = DownloadsFragment()
            settingsFragment = SettingsFragment()
            playlistDetailFragment = PlaylistDetailFragment()

            supportFragmentManager.beginTransaction()
                .add(R.id.fragmentContainer, discoverFragment, "discover")
                .add(R.id.fragmentContainer, playlistsFragment, "playlists").hide(playlistsFragment)
                .add(R.id.fragmentContainer, downloadsFragment, "downloads").hide(downloadsFragment)
                .add(R.id.fragmentContainer, settingsFragment, "settings").hide(settingsFragment)
                .add(R.id.fragmentContainer, playlistDetailFragment, "playlist_detail").hide(playlistDetailFragment)
                .commit()
        } else {
            discoverFragment = supportFragmentManager.findFragmentByTag("discover") as DiscoverFragment
            playlistsFragment = supportFragmentManager.findFragmentByTag("playlists") as PlaylistsFragment
            downloadsFragment = supportFragmentManager.findFragmentByTag("downloads") as DownloadsFragment
            settingsFragment = supportFragmentManager.findFragmentByTag("settings") as SettingsFragment
            playlistDetailFragment = supportFragmentManager.findFragmentByTag("playlist_detail") as PlaylistDetailFragment
        }
    }

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            val show = when (item.itemId) {
                R.id.nav_discover -> discoverFragment
                R.id.nav_playlists -> playlistsFragment
                R.id.nav_downloads -> {
                    downloadsFragment.loadDownloadedFiles()
                    downloadsFragment
                }
                R.id.nav_settings -> settingsFragment
                else -> return@setOnItemSelectedListener false
            }
            val all = listOf(discoverFragment, playlistsFragment, downloadsFragment, settingsFragment, playlistDetailFragment)
            val tx = supportFragmentManager.beginTransaction()
            all.forEach { if (it == show) tx.show(it) else tx.hide(it) }
            tx.commit()
            true
        }
    }

    private fun setupMediaController() {
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            controller?.let { 
                PlayerManager.attach(it)
            }
            PlayerManager.onTrackChanged = { track, index -> 
                updateMiniPlayer(track)
                discoverFragment.updatePlayingPosition(index)
                playlistDetailFragment.updatePlayingPosition(index)
            }
            PlayerManager.addPlaybackStateListener(playbackStateListener)
        }, MoreExecutors.directExecutor())
    }

    private fun setupMiniPlayer() {
        binding.miniPlayerPlayPause.setOnClickListener {
            PlayerManager.togglePlayPause()
        }
        binding.miniPlayerPrev.setOnClickListener {
            PlayerManager.playPrev()
        }
        binding.miniPlayerNext.setOnClickListener {
            PlayerManager.playNext()
        }
        
        binding.miniPlayerSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = PlayerManager.getDuration()
                    if (duration > 0) {
                        val newPos = (progress.toLong() * duration) / 1000
                        PlayerManager.seekTo(newPos)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { isUserSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { isUserSeeking = false }
        })
    }

    private fun wireCallbacks() {
        val playCallback: (Track, String) -> Unit = { track, url -> playTrack(track, url) }

        discoverFragment.onTrackSelected = playCallback
        
        supportFragmentManager.setFragmentResultListener("download_complete", this) { _, _ ->
            downloadsFragment.loadDownloadedFiles()
        }

        downloadsFragment.onFileSelected = playCallback
        downloadsFragment.onFileDeleted = { path -> discoverFragment.resetDownloadByPath(path) }
        
        playlistDetailFragment.onTrackSelected = playCallback
        playlistsFragment.onPlaylistClick = { playlist -> openPlaylistDetail(playlist) }
        playlistDetailFragment.onBack = {
            supportFragmentManager.beginTransaction()
                .hide(playlistDetailFragment).show(playlistsFragment).commit()
            binding.bottomNav.selectedItemId = R.id.nav_playlists
        }
    }

    private fun openPlaylistDetail(playlist: PlaylistEntity) {
        playlistDetailFragment.playlistId = playlist.id
        playlistDetailFragment.playlistName = playlist.name
        val all = listOf(discoverFragment, playlistsFragment, downloadsFragment, settingsFragment)
        val tx = supportFragmentManager.beginTransaction()
        all.forEach { tx.hide(it) }
        tx.show(playlistDetailFragment).commit()
    }

    fun playTrack(track: Track, url: String) {
        val c = controller ?: return
        val mediaItem = androidx.media3.common.MediaItem.Builder()
            .setUri(url).setMediaId(track.id).build()
        c.setMediaItem(mediaItem)
        c.prepare()
        c.play()
        updateMiniPlayer(track)
    }

    private fun updateMiniPlayer(track: Track) {
        runOnUiThread {
            binding.miniPlayer.visibility = View.VISIBLE
            binding.miniPlayerTitle.text = track.name
            binding.miniPlayerArtist.text = track.artistName
            if (track.image.isNotEmpty()) {
                binding.miniPlayerArt.load(track.image) {
                    transformations(RoundedCornersTransformation(12f))
                }
            }
            binding.miniPlayerSeekBar.progress = 0
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        PlayerManager.removePlaybackStateListener(playbackStateListener)
        mainHandler.removeCallbacks(updateProgressRunnable)
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}
