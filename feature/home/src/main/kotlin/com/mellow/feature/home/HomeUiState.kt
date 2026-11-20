package com.mellow.feature.home

import androidx.compose.runtime.Immutable
import com.mellow.core.model.Album
import com.mellow.core.model.Track
import com.mellow.core.playback.QueueSource

sealed interface HomeUiState {

    /** The first page has not been read yet. */
    data object Loading : HomeUiState

    /** Mellow has not been allowed to read the phone's music. */
    data object NeedsAccess : HomeUiState

    /** Allowed in, and there is no music on the phone. */
    data object Empty : HomeUiState

    /**
     * @property tracks the newest tracks on the phone, as far as has been read. The list
     *   grows as the user scrolls toward its end.
     * @property moreTracks false once the last track on the phone has been read.
     * @property albums and [moreAlbums]: the same, for the shelf.
     */
    @Immutable
    data class Ready(
        /** The album the newest track belongs to. */
        val featured: Album,
        val albums: List<Album>,
        val moreAlbums: Boolean,
        val tracks: List<Track>,
        val moreTracks: Boolean,
        /** The track that is loaded in the player, playing or paused. */
        val currentTrackId: String?,
        /** Which list the player's queue was started from, as a `QueueSource` name. */
        val playingFrom: String?,
        val isPlaying: Boolean,
    ) : HomeUiState {

        /** True while [album] is what is playing: started as an album, and sounding now. */
        fun isPlaying(album: Album): Boolean = isPlaying && playingFrom == QueueSource.album(album.id)
    }
}
