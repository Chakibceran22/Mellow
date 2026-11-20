package com.mellow.core.data

import android.content.IntentSender
import com.mellow.core.model.Album
import com.mellow.core.model.SearchResults
import com.mellow.core.model.Track
import kotlinx.coroutines.flow.StateFlow

/**
 * The music stored on this phone.
 *
 * Nothing here holds the library in memory. Every function reads a small slice of the
 * phone's own media index when asked, on a background thread, and returns descriptions
 * only: no audio file is opened until a track is played, and no cover is decoded until it
 * is shown. Screens ask for a page, show it, and ask for the next as the user gets near
 * the end. [com.mellow.core.data.paging.Pager] does that bookkeeping.
 *
 * A track is shown as the user has edited it, where they have: see [rename] and
 * [setCoverPicture]. Those edits live in Mellow and never touch the files.
 *
 * All functions are safe to call from the main thread and return empty results while
 * [access] is false.
 */
interface LibraryRepository {

    /** Whether the user has allowed Mellow to read the phone's music. */
    val access: StateFlow<Boolean>

    /**
     * Changes every time the music on the phone does (a file added, removed or retagged),
     * and when [access] is granted. Collect it and reload whatever is on screen.
     */
    val revision: StateFlow<Int>

    /** Checks the permission again. Call after asking for it, and when the app returns to the front. */
    fun refreshAccess()

    /**
     * Has the phone look through its storage again for music it has not listed yet, and
     * waits for that, though not indefinitely: on a phone holding a great many files the
     * search carries on in the background and what it finds shows up as it is found.
     * [revision] changes either way, so whatever is on screen reloads.
     *
     * For the case this exists for: a song saved by another app that the phone has not
     * got round to noticing.
     */
    suspend fun rescan()

    /** A page of every track on the phone, most recently added first. */
    suspend fun tracks(offset: Int, limit: Int): List<Track>

    /** A page of every album on the phone, by title. */
    suspend fun albums(offset: Int, limit: Int): List<Album>

    suspend fun album(id: String): Album?

    /** An album's tracks in playing order. */
    suspend fun albumTracks(albumId: String): List<Track>

    /** Those of these tracks that are still on the phone, in no particular order. */
    suspend fun tracksById(ids: Collection<String>): List<Track>

    /** What the files themselves say about these tracks, leaving out anything edited in Mellow. */
    suspend fun tracksAsFiled(ids: Collection<String>): List<Track>

    /**
     * Every track whose file gives it exactly this title, ignoring case, described as
     * filed. For finding a track again from a backup, which records what the file says.
     */
    suspend fun tracksTitled(title: String): List<Track>

    /**
     * Changes what a track is called in Mellow. Passing back what the file says (or a
     * blank) for any of the three undoes the change to that one.
     */
    suspend fun rename(trackId: String, title: String, artist: String, album: String)

    /**
     * Gives these tracks a picture from the phone as their cover, in place of their
     * album's. The picture is copied into Mellow's own storage.
     *
     * @param pictureUri where to read the picture, as handed over by the system's picker.
     * @return false if it could not be read as an image.
     */
    suspend fun setCoverPicture(trackIds: Collection<String>, pictureUri: String): Boolean

    /** Puts these tracks back on their own covers. */
    suspend fun clearCoverPicture(trackIds: Collection<String>)

    /** Undoes everything edited about these tracks: names and cover. */
    suspend fun undoEdits(trackIds: Collection<String>)

    /**
     * Tracks and albums whose title, artist or album name contains [query], ignoring case.
     * A blank query finds nothing. At most [limit] of each.
     */
    suspend fun search(query: String, limit: Int = 30): SearchResults

    /** False on versions of Android that give an app no way to ask to delete music it did not make. */
    val canDeleteFiles: Boolean

    /**
     * Prepares the deletion of these tracks' files from the phone.
     *
     * An app may not delete the user's music by itself. What comes back is Android's own
     * confirmation screen, to be launched for a result: Android names how many files, the
     * user agrees or not, and if they agree Android does the deleting. [revision] changes
     * when it has.
     *
     * @return null when there is nothing to delete or [canDeleteFiles] is false.
     */
    fun deleteRequest(trackIds: Collection<String>): IntentSender?
}
