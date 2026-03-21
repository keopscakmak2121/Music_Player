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
    
    // URL Önbelleği
    private val urlCache = mutableMapOf<String, String>()

    // URL Çözücü
    var urlResolver: ((Track, (String) -> Unit) -> Unit)? = null

    private var retryCount = 0
    private const val MAX_RETRY = 1
    private val mainHandler = Handler(Looper.getMainLooper())

    private val playbackStateListeners = mutableListOf<(Boolean) -> Unit>()

    fun addPlaybackStateListener(listener: (Boolean) -> Unit) {
        if (!playbackStateListeners.contains(listener)) {
            playbackStateListeners.add(listener)
        }
    }

    fun removePlaybackStateListener(listener: (Boolean) -> Unit) {
        playbackStateListeners.remove(listener)
    }

    fun attach(controller: MediaController) {
        this.controller = controller
        controller.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    retryCount = 0
                    playNext()
                }
                notifyListeners(isPlaying())
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                notifyListeners(isPlaying)
                if (isPlaying) {
                    prefetchNextTrack()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val isSourceError = error.errorCode in
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS..
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED

                if (isSourceError && retryCount < MAX_RETRY) {
                    retryCount++
                    val track = currentQueue.getOrNull(currentIndex) ?: return
                    urlCache.remove(track.id)
                    mainHandler.postDelayed({ resolveAndPlay(track) }, 500)
                } else {
                    retryCount = 0
                    playNext()
                }
            }
        })
    }

    private fun notifyListeners(isPlaying: Boolean) {
        mainHandler.post {
            playbackStateListeners.forEach { it.invoke(isPlaying) }
        }
    }

    fun isPlaying(): Boolean = controller?.isPlaying ?: false
    fun getDuration(): Long = controller?.duration ?: 0L
    fun getCurrentPosition(): Long = controller?.currentPosition ?: 0L
    fun seekTo(position: Long) { controller?.seekTo(position) }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun playQueue(tracks: List<Track>, startIndex: Int) {
        if (tracks.isEmpty()) return
        currentQueue = tracks
        currentIndex = startIndex
        retryCount = 0
        if (playMode == PlayMode.SHUFFLE) generateShuffledIndices()
        resolveAndPlay(currentQueue[currentIndex])
    }

    private fun generateShuffledIndices() {
        if (currentQueue.isEmpty()) return
        shuffledIndices = currentQueue.indices.toMutableList().shuffled()
    }

    fun playNext() {
        if (currentQueue.isEmpty()) return
        retryCount = 0
        currentIndex = getNextIndex()
        resolveAndPlay(currentQueue[currentIndex])
    }

    fun playPrev() {
        if (currentQueue.isEmpty()) return
        retryCount = 0
        currentIndex = getPrevIndex()
        resolveAndPlay(currentQueue[currentIndex])
    }

    private fun getNextIndex(): Int {
        if (currentQueue.isEmpty()) return -1
        return when (playMode) {
            PlayMode.SEQUENTIAL -> if (currentIndex + 1 < currentQueue.size) currentIndex + 1 else 0
            PlayMode.SHUFFLE -> {
                val pos = shuffledIndices.indexOf(currentIndex)
                if (pos == -1) 0 else shuffledIndices[(pos + 1) % shuffledIndices.size]
            }
        }
    }

    private fun getPrevIndex(): Int {
        if (currentQueue.isEmpty()) return -1
        return when (playMode) {
            PlayMode.SEQUENTIAL -> if (currentIndex - 1 >= 0) currentIndex - 1 else currentQueue.size - 1
            PlayMode.SHUFFLE -> {
                val pos = shuffledIndices.indexOf(currentIndex)
                if (pos == -1) 0 else shuffledIndices[if (pos - 1 >= 0) pos - 1 else shuffledIndices.size - 1]
            }
        }
    }

    private fun prefetchNextTrack() {
        if (currentQueue.isEmpty() || currentIndex == -1) return
        
        val nextIdx = getNextIndex()
        // Eğer geçerli bir sonraki indeks varsa ve şu anki şarkıdan farklıysa
        if (nextIdx != -1 && nextIdx != currentIndex && nextIdx < currentQueue.size) {
            val nextTrack = currentQueue[nextIdx]
            if (!urlCache.containsKey(nextTrack.id) && (nextTrack.audio.isEmpty() || nextTrack.audio.startsWith("http"))) {
                urlResolver?.invoke(nextTrack) { url ->
                    urlCache[nextTrack.id] = url
                }
            }
        }
    }

    private fun resolveAndPlay(track: Track) {
        if (track.audio.isNotEmpty() && !track.audio.startsWith("http")) {
            play(track, track.audio)
            return
        }

        urlCache[track.id]?.let { cachedUrl ->
            play(track, cachedUrl)
            return
        }

        urlResolver?.invoke(track) { url -> 
            urlCache[track.id] = url
            play(track, url)
        }
    }

    private fun play(track: Track, url: String) {
        val c = controller ?: return
        mainHandler.post {
            val mediaItem = MediaItem.Builder().setUri(url).setMediaId(track.id).build()
            c.setMediaItem(mediaItem)
            c.prepare()
            c.play()
            onTrackChanged?.invoke(track, currentIndex)
        }
    }

    var onTrackChanged: ((Track, Int) -> Unit)? = null
}
