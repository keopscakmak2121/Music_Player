package com.example.musicplayer

import androidx.media3.common.MediaItem
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

    fun attach(controller: MediaController) {
        this.controller = controller
        controller.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    playNext()
                }
            }
        })
    }

    fun playQueue(tracks: List<Track>, startIndex: Int) {
        currentQueue = tracks
        currentIndex = startIndex
        if (playMode == PlayMode.SHUFFLE) {
            generateShuffledIndices()
        }
        val track = currentQueue[currentIndex]
        resolveAndPlay(track)
    }

    private fun generateShuffledIndices() {
        if (currentQueue.isEmpty()) return
        val indices = currentQueue.indices.toMutableList()
        // Şu anki şarkıyı başta tutmak isteyebiliriz, ama basitçe karıştırıyoruz:
        shuffledIndices = indices.shuffled()
    }

    fun playNext() {
        if (currentQueue.isEmpty()) return
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
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMediaId(track.id)
            .build()
        c.setMediaItem(mediaItem)
        c.prepare()
        c.play()
        onTrackChanged?.invoke(track, currentIndex)
    }

    var onTrackChanged: ((Track, Int) -> Unit)? = null
}
