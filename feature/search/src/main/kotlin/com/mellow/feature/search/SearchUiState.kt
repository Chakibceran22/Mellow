package com.mellow.feature.search

import androidx.compose.runtime.Immutable
import com.mellow.core.model.Album
import com.mellow.core.model.Track

/**
 * What the Search page shows under its bar. The text being typed is not in here: it is
 * held separately, as [SearchViewModel.query], because it changes on every keystroke and
 * these change only when an answer arrives.
 */
sealed interface SearchUiState {

    /** Mellow has not been allowed to read the phone's music, so there is nothing to look through. */
    data object NoAccess : SearchUiState

    /** Nothing has been typed. */
    data object Idle : SearchUiState

    /** The first answer to a query is on its way. Later answers replace the one on screen directly. */
    data object Searching : SearchUiState

    /** The library was searched for [query] and holds nothing like it. */
    data class NothingFound(val query: String) : SearchUiState

    @Immutable
    data class Results(
        /** The text these results answer, which may be a keystroke behind the field. */
        val query: String,
        val albums: List<Album>,
        val tracks: List<Track>,
        /** The track that is loaded in the player, playing or paused. */
        val currentTrackId: String?,
        /** The album that track belongs to. */
        val currentAlbumId: String?,
        val isPlaying: Boolean,
    ) : SearchUiState
}
