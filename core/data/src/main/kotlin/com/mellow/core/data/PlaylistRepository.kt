package com.mellow.core.data

import com.mellow.core.model.Playlist
import com.mellow.core.model.PlaylistCover
import com.mellow.core.model.Track
import kotlinx.coroutines.flow.StateFlow

/**
 * The playlists the user has made.
 *
 * A playlist is Mellow's own: a name and an ordered list of tracks, kept in the app's
 * storage. It never touches the audio files. Deleting a playlist, or taking a track out of
 * one, leaves the music on the phone exactly as it was.
 *
 * All functions are safe to call from the main thread.
 */
interface PlaylistRepository {

    /** Changes every time any playlist does. Collect it and reload whatever is on screen. */
    val revision: StateFlow<Int>

    /** Every playlist, most recently changed first. */
    suspend fun all(): List<Playlist>

    /**
     * A page of a playlist's tracks, in the order they were added. Tracks no longer on the
     * phone are left out, and do not count toward [offset].
     *
     * As everywhere in Mellow these are descriptions only: nothing is opened until a
     * track is played.
     */
    suspend fun tracks(playlistId: String, offset: Int, limit: Int): List<Track>

    /** Every track of a playlist at once. For when the whole list is needed, such as shuffling all of it. */
    suspend fun tracks(playlistId: String): List<Track>

    suspend fun create(name: String): Playlist

    suspend fun rename(playlistId: String, name: String)

    /** Deletes the playlist. The music in it is untouched. */
    suspend fun delete(playlistId: String)

    /**
     * Adds tracks to the end of a playlist, skipping any it already holds.
     *
     * @return how many were actually added.
     */
    suspend fun add(playlistId: String, trackIds: Collection<String>): Int

    suspend fun remove(playlistId: String, trackIds: Collection<String>)

    /** These tracks are gone from the phone: take them out of every playlist. */
    suspend fun forget(trackIds: Collection<String>)

    /** Sets the cover to follow the playlist's songs, or to one song's cover. For a picture, use [setCoverPicture]. */
    suspend fun setCover(playlistId: String, cover: PlaylistCover)

    /**
     * Makes a picture from the phone the playlist's cover. The picture is copied into
     * Mellow's own storage, so the cover survives the original being moved or deleted.
     *
     * @param pictureUri where to read the picture, as handed over by the system's picker.
     * @return false if it could not be read as an image.
     */
    suspend fun setCoverPicture(playlistId: String, pictureUri: String): Boolean

    /** How much there is that only Mellow knows, and that a backup would save. */
    suspend fun backupContents(): BackupContents

    /**
     * Writes everything that lives only inside Mellow into one file: every playlist (name,
     * chosen cover, and each track described well enough to be found again), and every
     * song the user has edited (its new names and cover). Never the audio itself. The file
     * is for bringing it all back after the app has been removed, or onto another phone
     * that holds the same music.
     *
     * @param destinationUri a document the user chose to save into.
     */
    suspend fun exportTo(destinationUri: String): ExportOutcome

    /**
     * Reads a file written by [exportTo] and adds what it holds: playlists that are not
     * here are made, ones that already are (by name) gain any tracks they lack, and song
     * edits are applied to the songs they belong to. Nothing is ever removed, so importing
     * the same file twice changes nothing.
     */
    suspend fun importFrom(sourceUri: String): ImportOutcome
}

/**
 * @property songs how many songs carry edits of the user's.
 */
data class BackupContents(val playlists: Int, val songs: Int) {
    val isEmpty: Boolean get() = playlists == 0 && songs == 0
}

sealed interface ExportOutcome {
    data class Saved(val playlists: Int, val songs: Int) : ExportOutcome

    /** The music cannot be read, so the tracks could not be described. Nothing was written. */
    data object NeedsAccess : ExportOutcome

    /** The file could not be written where the user chose. */
    data object Failed : ExportOutcome
}

sealed interface ImportOutcome {
    /**
     * @property playlists how many the file held.
     * @property tracks how many tracks were added to playlists.
     * @property missing how many tracks in the file are not on this phone.
     * @property songs how many edited songs had their edits put back.
     */
    data class Restored(val playlists: Int, val tracks: Int, val missing: Int, val songs: Int) : ImportOutcome

    /** The music cannot be read, so the tracks in the file could not be looked for. */
    data object NeedsAccess : ImportOutcome

    /** The file opened, but it is not one of ours. */
    data object NotABackup : ImportOutcome

    /** The file could not be opened at all. */
    data object Unreadable : ImportOutcome
}
