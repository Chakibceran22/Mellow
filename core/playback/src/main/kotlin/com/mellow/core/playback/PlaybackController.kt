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
    /** Whether there is a track after this one in the playing order, shuffle and repeat considered. */
    val hasNext: Boolean = false,
    /** Whether there is a track before this one in the playing order. */
    val hasPrevious: Boolean = false,
) {
    val trackId: String? get() = track?.id
}

/** Names for where a queue came from. */
object QueueSource {
    /** The list of every song on the phone. */
    const val Library = "library"

    fun album(albumId: String) = "album:$albumId"

    fun playlist(playlistId: String) = "playlist:$playlistId"

    /** Nothing was playing and the user queued some songs: the queue is all there is. */
    const val Queue = "queue"
}

/**
 * One song that is waiting to play.
 *
 * @property key a name for this place in line that stays the same while the song waits,
 *   however the line is reordered around it. The same song queued twice has two keys.
 */
data class QueuedTrack(val key: String, val track: Track)

/**
 * What plays after the track that is on.
 *
 * @property queued the user's own queue: songs they asked for with "play next" or "add to
 *   queue", in the order they will play. These come first.
 * @property then the first of what follows the user's queue: the rest of the album,
 *   playlist or list that was put on, in the order it will play (shuffled, if shuffle is on).
 * @property more how many more there are after [then] that are not listed.
 * @property from what [then] is the rest of, in a few words ("Night Bus"), when that is known.
 */
data class UpNext(
    val queued: List<QueuedTrack> = emptyList(),
    val then: List<QueuedTrack> = emptyList(),
    val more: Int = 0,
    val from: String? = null,
)

/** How many bands [PlaybackController.audioSpectrum] divides the sound into. */
const val SpectrumBands = 24

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

    /** What is lined up after the current track. */
    val upNext: StateFlow<UpNext>

    /** How loud the audio leaving the player is at this moment, 0..1. Cheap enough to call every frame. */
    fun audioLevel(): Float

    /**
     * What the sound coming out of the speaker is made of at this moment: how strong each
     * part of it is, from the lowest notes to the highest. Fills the first [SpectrumBands]
     * of [into] with levels from 0 to 1 and returns true; or returns false and leaves
     * [into] alone when there is nothing sounding. The levels are scaled to the music, so
     * a quiet record fills the range as a loud one does.
     *
     * Cheap enough to call every frame, and it allocates nothing. The measuring behind it
     * only runs while this is being called.
     */
    fun audioSpectrum(into: FloatArray): Boolean

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
     * Whatever the user has queued themselves stays queued: it plays after the track this
     * starts on, and the new list carries on after that.
     *
     * @param source the list this queue comes from, as a [QueueSource] name. It is handed
     *   back in [PlaybackState.source] for as long as this queue plays.
     * @param sourceLabel what that list is called, for showing what is coming up ("Night Bus").
     */
    fun play(queue: List<Track>, startIndex: Int? = 0, tail: QueueTail? = null, source: String? = null, sourceLabel: String? = null)

    /**
     * Puts these tracks at the front of the user's queue, in this order: the first of them
     * plays as soon as the current track ends. With nothing on, they start playing.
     *
     * A track that is already coming up is moved here rather than played twice, and the
     * track that is on is left out: it cannot follow itself.
     */
    fun playNext(tracks: List<Track>)

    /** The same, at the back of the user's queue: after everything they have already queued. */
    fun addToQueue(tracks: List<Track>)

    /** Moves a track within the user's queue. Positions count from 0 at the front of [UpNext.queued]. */
    fun moveQueued(from: Int, to: Int)

    /** Takes one track out of the user's queue. */
    fun removeQueued(key: String)

    /** Empties the user's queue. What was playing carries on, with what followed it. */
    fun clearQueued()

    /**
     * Plays one of the tracks in [upNext] now. The rest of the user's queue is not lost by
     * it: whatever of it was still waiting plays after this track.
     */
    fun skipTo(key: String)

    /** Takes these tracks out of the queue, moving on if one of them is playing. For files that no longer exist. */
    fun removeFromQueue(trackIds: Set<String>)

    /**
     * Tells the player that these tracks are now described differently (a new title, a new
     * cover). Any of them in the queue is brought up to date where it stands; the one that
     * is playing carries on without a break.
     */
    fun refresh(tracks: Collection<Track>)

    /**
     * Has the player let go of these tracks' files, because they are about to be written
     * to. Only matters if one of them is loaded (the track that is on, or the one lined up
     * behind it): the player then closes it and keeps its place. A file rewritten under a
     * player that still has it open plays wrong from then on.
     *
     * Always follow it with [pickUp], whether or not the files were changed in the end.
     */
    fun letGo(trackIds: Set<String>)

    /** After [letGo]: opens the track again and carries on from the same spot, playing if it was playing. */
    fun pickUp()

    fun togglePlayPause()

    fun next()

    /** Restarts the track if it is under way, otherwise steps back one, as a player's back button does. */
    fun previous()

    /** Steps back to the track before this one, however far into this one it is. For a gesture that means "the one before", never "again". */
    fun previousTrack()

    fun seekTo(positionMs: Long)

    fun toggleShuffle()

    /** Steps through off, repeat all, repeat one. */
    fun cycleRepeat()
}
