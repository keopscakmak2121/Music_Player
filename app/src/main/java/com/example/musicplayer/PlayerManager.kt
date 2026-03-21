package com.example.musicplayer

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.example.musicplayer.model.Track

enum class PlayMode { SEQUENTIAL, SHUFFLE }

object PlayerManager {

    var playMode: PlayMode = PlayMode.SEQUENTIAL
        set(value) {
            field = value
            if (value == PlayMode.SHUFFLE && currentQueue.isNotEmpty()) {
                generateShuffledIndices()
            }
        }

    var currentQueue: List<Track> = emptyList()
    var currentIndex: Int = -1
    private var shuffledIndices: List<Int> = emptyList()

    private var controller: MediaController? = null
    var urlResolver: ((Track, (String) -> Unit) -> Unit)? = null

    // Expire olan URL için taze çekip tekrar dene
    private var retryCount = 0
    private const val MAX_RETRY = 1
    private val mainHandler = Handler(Looper.getMainLooper())

    fun attach(controller: MediaController) {
        this.controller = controller
        controller.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    retryCount = 0
                    playNext()
                }
                onPlaybackStateChangedListener?.invoke(isPlaying())
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onPlaybackStateChangedListener?.invoke(isPlaying)
            }

            override fun onPlayerError(error: PlaybackException) {
                // IO/ağ hataları: expire olmuş URL veya geçici bağlantı sorunu
                val isSourceError = error.errorCode in
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS..
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED

                if (isSourceError && retryCount < MAX_RETRY) {
                    retryCount++
                    val track = currentQueue.getOrNull(currentIndex) ?: return
                    // Kısa gecikme sonrası URL'yi taze al ve tekrar başlat
                    mainHandler.postDelayed({ resolveAndPlay(track) }, 500)
                } else {
                    retryCount = 0
                    playNext()
                }
            }
        })
    }

    fun isPlaying(): Boolean = controller?.isPlaying ?: false

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            c.play()
        }
    }

    fun playQueue(tracks: List<Track>, startIndex: Int) {
        currentQueue = tracks
        currentIndex = startIndex
        retryCount = 0
        if (playMode == PlayMode.SHUFFLE) {
            generateShuffledIndices()
        }
        val track = currentQueue[currentIndex]
        resolveAndPlay(track)
    }

    private fun generateShuffledIndices() {
        if (currentQueue.isEmpty()) return
        shuffledIndices = currentQueue.indices.toMutableList().shuffled()
    }

    fun playNext() {
        if (currentQueue.isEmpty()) return
        retryCount = 0
        val nextIndex = when (playMode) {
            PlayMode.SEQUENTIAL -> {
                if (currentIndex + 1 < currentQueue.size) currentIndex + 1 else 0
            }
            PlayMode.SHUFFLE -> {
                val pos = shuffledIndices.indexOf(currentIndex)
                val nextPos = (pos + 1) % shuffledIndices.size
                shuffledIndices[nextPos]
            }
        }
        currentIndex = nextIndex
        val track = currentQueue[currentIndex]
        resolveAndPlay(track)
    }

    fun playPrev() {
        if (currentQueue.isEmpty()) return
        retryCount = 0
        val prevIndex = when (playMode) {
            PlayMode.SEQUENTIAL -> {
                if (currentIndex - 1 >= 0) currentIndex - 1 else currentQueue.size - 1
            }
            PlayMode.SHUFFLE -> {
                val pos = shuffledIndices.indexOf(currentIndex)
                val prevPos = if (pos - 1 >= 0) pos - 1 else shuffledIndices.size - 1
                shuffledIndices[prevPos]
            }
        }
        currentIndex = prevIndex
        val track = currentQueue[currentIndex]
        resolveAndPlay(track)
    }

    private fun resolveAndPlay(track: Track) {
        urlResolver?.invoke(track) { url ->
            play(track, url)
        }
    }

    private fun play(track: Track, url: String) {
        val c = controller ?: return
        // MediaController main thread'den çağrılmalı
        mainHandler.post {
            val mediaItem = MediaItem.Builder()
                .setUri(url)
                .setMediaId(track.id)
                .build()
            c.setMediaItem(mediaItem)
            c.prepare()
            c.play()
            onTrackChanged?.invoke(track, currentIndex)
        }
    }

    var onTrackChanged: ((Track, Int) -> Unit)? = null
    var onPlaybackStateChangedListener: ((Boolean) -> Unit)? = null
}
