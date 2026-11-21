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
     * Changes what a track is called in Mellow only, leaving its file alone. Passing back
     * what the file says (or a blank) for any of the three undoes the change to that one.
     * For phones where [canWriteToSongs] is false; elsewhere see [writeSongDetails].
     */
    suspend fun rename(trackId: String, title: String, artist: String, album: String)

    /**
     * False on versions of Android that give an app no way to ask to change music it did
     * not make (before Android 11). There a chosen cover is kept in Mellow, the old way:
     * see [setCoverPicture].
     */
    val canWriteToSongs: Boolean

    /**
     * Prepares a change to these tracks' files.
     *
     * An app may not alter the user's music by itself. What comes back is Android's own
     * question, "Allow Mellow to modify these audio files?", to be launched for a result.
     * If the answer is yes, the functions below that write to songs will be let through
     * for these tracks; without it they change nothing and say so.
     *
     * @return null when there is nothing to ask about or [canWriteToSongs] is false.
     */
    fun writeRequest(trackIds: Collection<String>): IntentSender?

    /**
     * Changes what one song is called and, if [pictureUri] is given, its cover, by writing
     * them into the song's own file. They are then true of the song itself: Android reads
     * them from the file, and so does every other app.
     *
     * Only what differs from what the file says is written; a blank name leaves that name
     * alone. The file is copied, changed and checked before it replaces the song, and
     * anything about it that is not fully understood leaves the song untouched. A kind of
     * file that cannot be written this way (anything but MP3, for now) keeps the change in
     * Mellow instead, as [rename] and [setCoverPicture] would.
     *
     * Call after the user has said yes to [writeRequest] for this track.
     */
    suspend fun writeSongDetails(trackId: String, title: String, artist: String, album: String, pictureUri: String?): SongChanges

    /**
     * Makes a picture from the phone the cover of these songs, written into the song files
     * as [writeSongDetails] writes one. Call after the user has said yes to [writeRequest].
     *
     * @param pictureUri where to read the picture, as handed over by the system's picker.
     */
    suspend fun writeCoverPicture(trackIds: Collection<String>, pictureUri: String): SongChanges

    /**
     * Gives back to each of these songs the picture it had before its cover was first
     * changed in Mellow: written back into the file if it was written there, or simply
     * shown again if the change was only ever kept in Mellow. Songs never changed are left
     * alone. Needs [writeRequest] for the ones [changesFromMellow] says are written.
     */
    suspend fun restoreCoverPicture(trackIds: Collection<String>): SongChanges

    /**
     * Gives back to each of these songs everything it said about itself before Mellow
     * first changed it: names and picture. A song whose file was changed comes out exactly
     * as it was, to the byte. Needs [writeRequest] like [restoreCoverPicture].
     */
    suspend fun restoreSongs(trackIds: Collection<String>): SongChanges

    /** Which of these songs have been changed in Mellow, and where the change lives. */
    suspend fun changesFromMellow(trackIds: Collection<String>): ChangesFromMellow

    /**
     * Songs with work waiting that needs [writeRequest]. Nothing is waiting when
     * [canWriteToSongs] is false.
     */
    suspend fun songsAwaitingWrite(): AwaitingWrite

    /** Does that waiting work. Call after the user has said yes to [writeRequest] for [songsAwaitingWrite]. */
    suspend fun writeAwaited(): SongChanges

    /**
     * Gives these tracks a picture from the phone as their cover without touching their
     * files: the picture is copied into Mellow's own storage and shown in place of the
     * song's. Only Mellow sees it. For phones where [canWriteToSongs] is false.
     *
     * @param pictureUri where to read the picture, as handed over by the system's picker.
     * @return false if it could not be read as an image.
     */
    suspend fun setCoverPicture(trackIds: Collection<String>, pictureUri: String): Boolean

    /** Drops a cover kept in Mellow by [setCoverPicture], so the song's own shows again. */
    suspend fun clearCoverPicture(trackIds: Collection<String>)

    /** Forgets everything Mellow keeps over these tracks' files: names and cover. No file is touched; see [restoreSongs]. */
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

/**
 * The songs, out of some that were asked about, that have been changed in Mellow.
 *
 * @property written those whose file holds a change. Going back means writing to the file again.
 * @property kept those with a change that is only kept in Mellow. Going back touches no file.
 * @property pictures those, of either kind, whose cover is one of the things changed.
 */
data class ChangesFromMellow(
    val written: Set<String> = emptySet(),
    val kept: Set<String> = emptySet(),
    val pictures: Set<String> = emptySet(),
) {
    val all: Set<String> get() = written + kept
}

/**
 * Songs with work waiting on the user's leave to write to them.
 *
 * @property editsToMove songs with a name or a cover changed back when Mellow kept such
 *   changes to itself (or brought in by a backup from then), not yet written into the song.
 * @property unfinished songs a write to which was cut short, by the app being closed in
 *   the middle of it. One of these may not play right until the write is finished.
 */
data class AwaitingWrite(val editsToMove: List<String> = emptyList(), val unfinished: List<String> = emptyList()) {
    val all: List<String> get() = (unfinished + editsToMove).distinct()
}

/**
 * What came of writing to a number of songs.
 *
 * @property written how many now hold what was asked.
 * @property keptInMellow how many are a kind of file that cannot be written to, and had
 *   the change kept in Mellow instead.
 * @property failed how many could not be changed and were left as they were.
 * @property notAllowed true if Android refused because the user had not allowed it.
 * @property pictureUnreadable true if the chosen picture could not be opened as an image.
 */
data class SongChanges(
    val written: Int = 0,
    val keptInMellow: Int = 0,
    val failed: Int = 0,
    val notAllowed: Boolean = false,
    val pictureUnreadable: Boolean = false,
) {
    val changed: Int get() = written + keptInMellow

    /** The two taken together, for one piece of work done in two calls. */
    operator fun plus(other: SongChanges) = SongChanges(
        written = written + other.written,
        keptInMellow = keptInMellow + other.keptInMellow,
        failed = failed + other.failed,
        notAllowed = notAllowed || other.notAllowed,
        pictureUnreadable = pictureUnreadable || other.pictureUnreadable,
    )
}
