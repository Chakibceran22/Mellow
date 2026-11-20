package com.mellow.feature.library

import androidx.compose.runtime.Immutable
import com.mellow.core.model.Playlist
import com.mellow.core.model.Track

@Immutable
data class LibraryUiState(
    /** Null until they have been read. */
    val playlists: List<Playlist>? = null,
    /** The playlist whose page is showing, if one is. */
    val open: OpenPlaylist? = null,
    /** Whether the player is set to shuffle. The playlist page's toggle shows and sets this. */
    val shuffle: Boolean = false,
    /** The track that is loaded in the player, playing or paused. */
    val currentTrackId: String? = null,
    val isPlaying: Boolean = false,
)

/**
 * @property tracks the playlist's tracks as far as they have been read: the page grows it
 *   as the user scrolls toward its end. Null until the first of them arrive.
 * @property moreTracks false once the last track has been read.
 * @property playing true while the track that is sounding belongs to this playlist.
 */
@Immutable
data class OpenPlaylist(
    val playlist: Playlist,
    val tracks: List<Track>?,
    val moreTracks: Boolean = false,
    val playing: Boolean = false,
)

/** A question the Library is asking in a sheet. One at a time. */
sealed interface LibrarySheet {

    /** Which playlist should these tracks go into? */
    data class AddTo(val trackIds: Set<String>) : LibrarySheet

    /**
     * What should the playlist be called?
     *
     * @property renaming the playlist being renamed, or null when a new one is being made.
     * @property trackIds tracks to put straight into a new playlist.
     */
    data class Name(val renaming: Playlist?, val trackIds: Set<String> = emptySet()) : LibrarySheet

    /** Really delete this playlist? */
    data class ConfirmDelete(val playlist: Playlist) : LibrarySheet

    /** What should this playlist's cover be? */
    data class Cover(val playlistId: String) : LibrarySheet

    /** What should be changed about these songs: their names (one song) or their cover (any number)? */
    data class EditSongs(val trackIds: Set<String>) : LibrarySheet
}

/**
 * The songs being edited, read when the sheet opens.
 *
 * @property tracks as they are shown now, in the order they were picked.
 * @property asFiled the same songs as their files describe them, for showing what an edit
 *   changed and for putting it back.
 * @property albumMates for one song: how many other songs share its album.
 */
@Immutable
data class EditingSongs(
    val tracks: List<Track>,
    val asFiled: List<Track>,
    val albumMates: Int,
) {
    /** The song, when there is exactly one. */
    val single: Track? = tracks.singleOrNull()

    private val filedById = asFiled.associateBy(Track::id)

    /** True if any of them wears a picture chosen in Mellow instead of its album's cover. */
    val anyOwnPicture: Boolean = tracks.any { track -> filedById[track.id]?.let { it.artwork != track.artwork } == true }

    /** True if anything at all about any of them has been edited. */
    val anyEdited: Boolean = tracks.any { track -> filedById[track.id]?.let { it != track } == true }

    /** The albums they come from, each once. */
    val albums: List<String> = tracks.map(Track::album).distinct()

    fun asFiled(track: Track): Track? = filedById[track.id]
}

/** What to do about a song's cover when its edit is saved. */
sealed interface PictureChange {
    data object Keep : PictureChange

    /** Back to the cover that comes with its album. */
    data object UseItsOwn : PictureChange

    /** A picture from the phone, as handed over by the system's picker. */
    data class Use(val pictureUri: String) : PictureChange
}

/**
 * Something the Library did that the rest of the app should hear about.
 *
 * @property message what happened, to show the user for a moment.
 * @property selectionUsed true when it was done to the selected tracks, which should now be let go.
 */
data class LibraryEvent(
    val message: String,
    val selectionUsed: Boolean = false,
)
