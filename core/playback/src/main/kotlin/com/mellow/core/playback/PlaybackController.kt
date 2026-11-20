package com.mellow.core.playback

import com.mellow.core.model.RepeatMode
import com.mellow.core.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** What the player is doing right now. */
data class PlaybackState(
    /** The track that is loaded, playing or paused. Null when the queue is empty. */
    val track: Track? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.Off,
    /**
     * Which list the queue was started from, as one of the [QueueSource] names, or null
     * when it is not known. A screen compares this with its own name to tell "this is what
     * is playing" from "one of my songs happens to be playing from somewhere else".
     */
    val source: String? = null,
) {
    val trackId: String? get() = track?.id
}

/** Names for where a queue came from. */
object QueueSource {
    /** The list of every song on the phone. */
    const val Library = "library"

    fun album(albumId: String) = "album:$albumId"

    fun playlist(playlistId: String) = "playlist:$playlistId"
}

/**
 * Supplies the rest of a long queue a page at a time. Given how many tracks the queue
 * already holds, it returns the next few, or none once there are no more.
 */
typealias QueueTail = suspend (loaded: Int) -> List<Track>

/**
 * The app's remote control for playback.
 *
 * Audio is played by a foreground service that outlives the screen; this is the only
 * handle the rest of the app holds on it. Call everything from the main thread.
 */
interface PlaybackController {

    val state: StateFlow<PlaybackState>

    /** Position in the current track, in milliseconds, sampled for as long as it is collected. */
    val position: Flow<Long>

    /** How loud the audio leaving the player is at this moment, 0..1. Cheap enough to call every frame. */
    fun audioLevel(): Float

    /** Binds to the playback service. Safe to call repeatedly. */
    fun connect()

    /**
     * Replaces the queue with [queue] and starts playing at [startIndex].
     *
     * Pass a null [startIndex] to start wherever the playing order begins: the first track
     * when playing in order, a track picked at random when shuffle is on. That is what a
     * "play this list" button wants; a tap on one particular track wants its index.
     *
     * When [queue] is only the loaded part of a longer list, pass [tail]: the player asks
     * it for more as it nears the end of what it has, so the whole list plays through
     * without ever being read in one go.
     *
     * @param source the list this queue comes from, as a [QueueSource] name. It is handed
     *   back in [PlaybackState.source] for as long as this queue plays.
     */
    fun play(queue: List<Track>, startIndex: Int? = 0, tail: QueueTail? = null, source: String? = null)

    /** Takes these tracks out of the queue, moving on if one of them is playing. For files that no longer exist. */
    fun removeFromQueue(trackIds: Set<String>)

    /**
     * Tells the player that these tracks are now described differently (a new title, a new
     * cover). Any of them in the queue is brought up to date where it stands; the one that
     * is playing carries on without a break.
     */
    fun refresh(tracks: Collection<Track>)

    fun togglePlayPause()

    fun next()

    fun previous()

    fun seekTo(positionMs: Long)

    fun toggleShuffle()

    /** Steps through off, repeat all, repeat one. */
    fun cycleRepeat()
}
