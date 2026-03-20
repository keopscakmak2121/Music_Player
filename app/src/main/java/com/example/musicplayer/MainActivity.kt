package com.example.musicplayer

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
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

    private val discoverFragment = DiscoverFragment()
    private val playlistsFragment = PlaylistsFragment()
    private val downloadsFragment = DownloadsFragment()
    private val settingsFragment = SettingsFragment()
    private val playlistDetailFragment = PlaylistDetailFragment()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(this, "Bildirim izni verilmedi, kontrolcü görünmeyebilir.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkPermissions()
        setupMediaController()
        setupFragments()
        setupBottomNav()
        setupMiniPlayer()
        wireCallbacks()
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupFragments() {
        supportFragmentManager.beginTransaction()
            .add(R.id.fragmentContainer, discoverFragment, "discover")
            .add(R.id.fragmentContainer, playlistsFragment, "playlists").hide(playlistsFragment)
            .add(R.id.fragmentContainer, downloadsFragment, "downloads").hide(downloadsFragment)
            .add(R.id.fragmentContainer, settingsFragment, "settings").hide(settingsFragment)
            .add(R.id.fragmentContainer, playlistDetailFragment, "playlist_detail").hide(playlistDetailFragment)
            .commit()
    }

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            val show = when (item.itemId) {
                R.id.nav_discover -> discoverFragment
                R.id.nav_playlists -> playlistsFragment
                R.id.nav_downloads -> downloadsFragment
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
                // Set the URL resolver for PlayerManager to handle auto-next
                PlayerManager.urlResolver = { track, callback ->
                    // In this app, the track object already has the audio URL
                    callback(track.audio)
                }
            }
            PlayerManager.onTrackChanged = { track -> updateMiniPlayer(track) }
        }, MoreExecutors.directExecutor())
    }

    private fun setupMiniPlayer() {
        binding.miniPlayerPlayPause.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.isPlaying) {
                c.pause()
                binding.miniPlayerPlayPause.setImageResource(android.R.drawable.ic_media_play)
            } else {
                c.play()
                binding.miniPlayerPlayPause.setImageResource(android.R.drawable.ic_media_pause)
            }
        }
        
        binding.miniPlayer.setOnClickListener {
            // Expand player logic could go here
        }
    }

    private fun wireCallbacks() {
        val playCallback: (Track, String) -> Unit = { track, url -> playTrack(track, url) }

        discoverFragment.onTrackSelected = playCallback
        downloadsFragment.onFileSelected = playCallback
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
        playlistDetailFragment.onTrackSelected = { track, url -> playTrack(track, url) }

        val all = listOf(discoverFragment, playlistsFragment, downloadsFragment, settingsFragment)
        val tx = supportFragmentManager.beginTransaction()
        all.forEach { tx.hide(it) }
        tx.show(playlistDetailFragment).commit()
    }

    fun playTrack(track: Track, url: String) {
        val c = controller ?: run {
            Toast.makeText(this, "Player hazırlanıyor", Toast.LENGTH_SHORT).show()
            return
        }
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
            binding.miniPlayerPlayPause.setImageResource(android.R.drawable.ic_media_pause)
            if (track.image.isNotEmpty()) {
                binding.miniPlayerArt.load(track.image) {
                    transformations(RoundedCornersTransformation(8f))
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}
