package com.mellow.feature.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mellow.core.playback.PlaybackController
import com.mellow.core.playback.UpNext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Backs both faces of the player: the mini player and the full Now Playing screen.
 *
 * Everything it shows comes from the player itself, which carries each track's details
 * with it. Nothing is looked up in the library, so the player works the same whichever
 * screen started the music.
 */
class PlayerViewModel(
    private val playback: PlaybackController,
) : ViewModel() {

    val uiState: StateFlow<PlayerUiState> =
        playback.state.map { playback ->
            PlayerUiState(
                track = playback.track,
                isPlaying = playback.isPlaying,
                // The player only learns a file's length once it has opened it; until
                // then the library's figure keeps the scrubber from collapsing to zero.
                durationMs = playback.durationMs.takeIf { it > 0 } ?: playback.track?.durationMs ?: 0L,
                shuffle = playback.shuffle,
                repeat = playback.repeat,
                canSkipNext = playback.hasNext,
                canSkipPrevious = playback.hasPrevious,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerUiState())

    /** Milliseconds into the current track. Collect only while it is on screen. */
    val position: Flow<Long> = playback.position

    /** What plays after the current track: the user's own queue, then the rest of what was put on. */
    val upNext: StateFlow<UpNext> = playback.upNext

    /**
     * Whether the sheet of what is coming up is open. Compose state rather than a flow,
     * like the Library's sheets, and kept here so the shell's back button can close it.
     */
    var upNextOpen: Boolean by mutableStateOf(false)
        private set

    fun showUpNext() {
        upNextOpen = true
    }

    fun hideUpNext() {
        upNextOpen = false
    }

    /** A row of the queue was dropped in a new place. Positions count from 0 at the front of the queue. */
    fun moveQueued(from: Int, to: Int) = playback.moveQueued(from, to)

    fun removeQueued(key: String) = playback.removeQueued(key)

    fun clearQueued() = playback.clearQueued()

    /** Plays a song from the line-up now. */
    fun playQueued(key: String) = playback.skipTo(key)

    fun togglePlayPause() = playback.togglePlayPause()

    fun next() = playback.next()

    fun previous() = playback.previous()

    /** The track before this one, however far into this one it is. What a swipe back means. */
    fun previousTrack() = playback.previousTrack()

    /** The sound as it is right now, band by band. See [PlaybackController.audioSpectrum]. */
    fun audioSpectrum(into: FloatArray): Boolean = playback.audioSpectrum(into)

    fun seekTo(positionMs: Long) = playback.seekTo(positionMs)

    fun toggleShuffle() = playback.toggleShuffle()

    fun cycleRepeat() = playback.cycleRepeat()

    companion object {
        fun factory(playback: PlaybackController): ViewModelProvider.Factory =
            viewModelFactory { initializer { PlayerViewModel(playback) } }
    }
}
