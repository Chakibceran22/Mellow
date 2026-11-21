package com.mellow.feature.home

import androidx.compose.runtime.Immutable
import com.mellow.core.model.Album
import com.mellow.core.model.Playlist
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
     * @property shelf the user's playlists, then the albums as far as have been read.
     *   [moreAlbums] is false once the last album has been read.
     */
    @Immutable
    data class Ready(
        /**
         * What the card at the top shows: one of the user's own playlists, picked at random
         * when the app opens and kept for as long as it stays open. An album from the phone
         * only when they have no playlist with anything in it.
         */
        val featured: ShelfItem,
        val shelf: List<ShelfItem>,
        val moreAlbums: Boolean,
        val tracks: List<Track>,
        val moreTracks: Boolean,
        /** The track that is loaded in the player, playing or paused. */
        val currentTrackId: String?,
        /** Which list the player's queue was started from, as a `QueueSource` name. */
        val playingFrom: String?,
        val isPlaying: Boolean,
    ) : HomeUiState {

        /** True while [item] is what is playing: the queue was started from it, and it is sounding now. */
        fun isPlaying(item: ShelfItem): Boolean = isPlaying && playingFrom == item.key
    }
}

/** Something on the Albums shelf: an album from the phone, or a playlist the user made. */
@Immutable
sealed interface ShelfItem {

    /** Unique across both kinds, since an album and a playlist could share an id. Also its `QueueSource` name. */
    val key: String

    data class OfAlbum(val album: Album) : ShelfItem {
        override val key: String get() = QueueSource.album(album.id)
    }

    data class OfPlaylist(val playlist: Playlist) : ShelfItem {
        override val key: String get() = QueueSource.playlist(playlist.id)
    }
}
