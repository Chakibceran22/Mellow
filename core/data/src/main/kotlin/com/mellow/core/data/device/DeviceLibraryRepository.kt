package com.mellow.core.data.device

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.mellow.core.data.LibraryAccess
import com.mellow.core.data.AwaitingWrite
import com.mellow.core.data.ChangesFromMellow
import com.mellow.core.data.LibraryRepository
import com.mellow.core.data.SongChanges
import com.mellow.core.data.device.tags.Mp3Tag
import com.mellow.core.data.local.Pictures
import com.mellow.core.model.Album
import com.mellow.core.model.Artwork
import com.mellow.core.model.SearchResults
import com.mellow.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * The phone's own music, read from MediaStore.
 *
 * MediaStore is the index Android already keeps of every audio file on the device: titles,
 * artists, albums and durations, parsed once by the system when a file appears. Every
 * query here reads rows from that index and nothing else. No audio file is opened to list
 * it, however many there are; a track only carries the address ExoPlayer will open when it
 * is played. Covers work the same way: a track carries the address Android serves its own
 * picture from (the one inside its file), and the image is decoded by whichever screen
 * shows it, when it shows it.
 *
 * What the user changes about a song (its names, its cover) is written into the song's
 * file ([SongFiles]), which makes it true of the song everywhere, not only here.
 *
 * Results are paged in the query itself, so asking for sixty tracks reads sixty rows.
 *
 * What the user has edited about a track (see [TrackEdits]) is laid over each row as it is
 * read, so everything above this class sees the edited track and nothing else has to know.
 */
class DeviceLibraryRepository(context: Context) : LibraryRepository {

    private val context = context.applicationContext
    private val resolver: ContentResolver = this.context.contentResolver

    internal val pictures = Pictures(this.context)
    internal val edits = TrackEdits(this.context, pictures)
    private val songFiles = SongFiles(this.context)
    private val notes = this.context.getSharedPreferences("song-pictures", Context.MODE_PRIVATE)

    /**
     * How many times a picture has been written into a song since the app started. An
     * album's cover is one of its songs' pictures and is asked for at one fixed address;
     * putting this number on the address has it asked for afresh once it may have changed.
     */
    @Volatile
    private var pictureWrites = 0

    private val _access = MutableStateFlow(hasPermission())
    override val access: StateFlow<Boolean> = _access.asStateFlow()

    private val _revision = MutableStateFlow(0)
    override val revision: StateFlow<Int> = _revision.asStateFlow()

    private val mainThread = Handler(Looper.getMainLooper())
    private val announceChange = Runnable { _revision.update { it + 1 } }

    init {
        // A media scan reports every file it touches. Wait for it to go quiet, then say
        // once that the library changed.
        val observer = object : ContentObserver(mainThread) {
            override fun onChange(selfChange: Boolean) {
                mainThread.removeCallbacks(announceChange)
                mainThread.postDelayed(announceChange, ChangeQuietMs)
            }
        }
        resolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
    }

    override fun refreshAccess() {
        val granted = hasPermission()
        if (granted != _access.value) {
            _access.value = granted
            _revision.update { it + 1 }
        }
    }

    override suspend fun rescan() {
        if (_access.value) {
            // The whole of shared storage, because music is saved wherever the app that
            // saved it liked. Android only re-reads files that are new or have changed.
            val everything = Environment.getExternalStorageDirectory().absolutePath
            withTimeoutOrNull(RescanPatienceMs) {
                suspendCancellableCoroutine { done ->
                    MediaScannerConnection.scanFile(context, arrayOf(everything), null) { _, _ ->
                        if (done.isActive) done.resume(Unit)
                    }
                }
            }
        }
        _revision.update { it + 1 }
    }

    override suspend fun tracks(offset: Int, limit: Int): List<Track> = read(emptyList()) {
        queryTracks(selection = MusicOnly, args = null, order = NewestFirst, offset = offset, limit = limit).edited()
    }

    override suspend fun albums(offset: Int, limit: Int): List<Album> = read(emptyList()) {
        queryAlbums(selection = null, args = null, offset = offset, limit = limit)
    }

    override suspend fun album(id: String): Album? = read(null) {
        queryAlbums(selection = "${MediaStore.Audio.Albums._ID} = ?", args = arrayOf(id), offset = 0, limit = 1).firstOrNull()
    }

    override suspend fun albumTracks(albumId: String): List<Track> = read(emptyList()) {
        queryTracks(
            selection = "$MusicOnly AND ${MediaStore.Audio.Media.ALBUM_ID} = ?",
            args = arrayOf(albumId),
            order = AlbumOrder,
            offset = 0,
            limit = AlbumTrackCap,
        ).edited()
    }

    override suspend fun tracksById(ids: Collection<String>): List<Track> = read(emptyList()) { filedById(ids).edited() }

    override suspend fun tracksAsFiled(ids: Collection<String>): List<Track> = read(emptyList()) { filedById(ids) }

    private fun filedById(ids: Collection<String>): List<Track> =
        // A statement can only carry so many values, so a long list is asked for in pieces.
        ids.filter { it.toLongOrNull() != null }.chunked(IdChunk).flatMap { chunk ->
            queryTracks(
                selection = "${MediaStore.Audio.Media._ID} IN (${chunk.joinToString(",") { "?" }})",
                args = chunk.toTypedArray(),
                order = MediaStore.Audio.Media._ID,
                offset = 0,
                limit = chunk.size,
            )
        }

    override suspend fun rename(trackId: String, title: String, artist: String, album: String) {
        val id = trackId.toLongOrNull() ?: return
        val filed = tracksAsFiled(listOf(trackId)).firstOrNull() ?: return
        // Only a real difference from the file is kept; anything else means "as the file says".
        fun changed(typed: String, onFile: String) = typed.trim().takeIf { it.isNotEmpty() && it != onFile }
        withContext(Dispatchers.IO) {
            edits.rename(id, changed(title, filed.title), changed(artist, filed.artist), changed(album, filed.album))
        }
        _revision.update { it + 1 }
    }

    override val canWriteToSongs: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    override fun writeRequest(trackIds: Collection<String>): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val files = trackIds.mapNotNull(String::toLongOrNull)
            .map { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) }
        if (files.isEmpty()) return null
        return MediaStore.createWriteRequest(resolver, files).intentSender
    }

    override suspend fun writeSongDetails(trackId: String, title: String, artist: String, album: String, pictureUri: String?): SongChanges {
        val id = trackId.toLongOrNull() ?: return SongChanges()
        val filed = tracksAsFiled(listOf(trackId)).firstOrNull() ?: return SongChanges(failed = 1)
        val jpeg = if (pictureUri == null) {
            null
        } else {
            readPicture(pictureUri) ?: return SongChanges(pictureUnreadable = true)
        }
        // Only a real difference from the file is written. Anything else means "as it is".
        fun changed(typed: String, onFile: String) = typed.trim().takeIf { it.isNotEmpty() && it != onFile }
        val newTitle = changed(title, filed.title)
        val newArtist = changed(artist, filed.artist)
        val newAlbum = changed(album, filed.album)
        val changes = Mp3Tag.Changes(newTitle, newArtist, newAlbum, jpeg)
        if (changes.isNothing) {
            // The file already says all of this. Names Mellow was keeping over it are not wanted any more.
            withContext(Dispatchers.IO) { if (edits.all()[id]?.let { it.title ?: it.artist ?: it.album } != null) edits.rename(id, null, null, null) }
            _revision.update { it + 1 }
            return SongChanges()
        }
        return when (songFiles.write(id, changes)) {
            SongFiles.Outcome.Written -> {
                withContext(Dispatchers.IO) {
                    // The file speaks for itself now. What Mellow kept over it would hide it.
                    edits.rename(id, null, null, null)
                    if (jpeg != null) edits.usePicture(listOf(id), name = null)
                    if (edits.all()[id] == null) noteKeptInMellow(drop = listOf(id))
                }
                announceWrites(listOf(id))
                SongChanges(written = 1)
            }
            SongFiles.Outcome.NotSupported -> {
                // A kind of file that cannot be written to keeps the change the old way, in Mellow.
                withContext(Dispatchers.IO) {
                    edits.rename(id, newTitle, newArtist, newAlbum)
                    if (jpeg != null) edits.setPicture(listOf(id), jpeg)
                    noteKeptInMellow(add = listOf(id))
                }
                _revision.update { it + 1 }
                SongChanges(keptInMellow = 1)
            }
            SongFiles.Outcome.NotAllowed -> SongChanges(notAllowed = true)
            SongFiles.Outcome.CannotRead, SongFiles.Outcome.Failed -> SongChanges(failed = 1)
        }
    }

    override suspend fun writeCoverPicture(trackIds: Collection<String>, pictureUri: String): SongChanges {
        val ids = trackIds.mapNotNull(String::toLongOrNull)
        if (ids.isEmpty()) return SongChanges()
        val jpeg = readPicture(pictureUri)
            ?: return SongChanges(pictureUnreadable = true)
        val batch = writeEach(ids) { Mp3Tag.Changes(picture = jpeg) }
        withContext(Dispatchers.IO) {
            if (batch.written.isNotEmpty()) edits.usePicture(batch.written, name = null)
            // One stored copy serves every song that could not take the picture itself.
            if (batch.unfit.isNotEmpty()) edits.setPicture(batch.unfit, jpeg)
            noteKeptInMellow(add = batch.unfit, drop = batch.written.filter { edits.all()[it] == null })
        }
        announceWrites(batch.written)
        return batch.report()
    }

    override suspend fun restoreCoverPicture(trackIds: Collection<String>): SongChanges =
        restore(trackIds, setOf(Mp3Tag.Part.Picture)) { inMellow -> edits.usePicture(inMellow, name = null) }

    override suspend fun restoreSongs(trackIds: Collection<String>): SongChanges =
        restore(trackIds, setOf(Mp3Tag.Part.Names, Mp3Tag.Part.Picture)) { inMellow -> edits.undo(inMellow) }

    /**
     * Returns [parts] of these songs to what they were before Mellow changed them: in the
     * file, for the songs whose file holds the change, and by [forget]ting what Mellow
     * keeps over the file for the ones that have such a thing.
     */
    private suspend fun restore(trackIds: Collection<String>, parts: Set<Mp3Tag.Part>, forget: (List<Long>) -> Unit): SongChanges {
        val ids = trackIds.mapNotNull(String::toLongOrNull)
        val written = ArrayList<Long>()
        var failed = 0
        var notAllowed = false
        for (id in ids) {
            // Only where that part is in fact not what it was. Rewriting a file to say what it says already is a risk for nothing.
            if (songFiles.changedParts(id).none { it in parts }) continue
            when (songFiles.putBack(id, parts)) {
                SongFiles.Outcome.Written -> written += id
                SongFiles.Outcome.NotAllowed -> notAllowed = true
                SongFiles.Outcome.Failed -> failed++
                // Nothing left to go back to: the song has gone, or is not the song it was.
                SongFiles.Outcome.NotSupported, SongFiles.Outcome.CannotRead -> Unit
            }
        }
        val shownAgain = withContext(Dispatchers.IO) {
            val kept = edits.all()
            val inMellow = ids.filter { id ->
                val edit = kept[id] ?: return@filter false
                Mp3Tag.Part.Names in parts || edit.picture != null
            }
            if (inMellow.isNotEmpty()) forget(inMellow)
            noteKeptInMellow(drop = ids.filter { edits.all()[it] == null })
            inMellow
        }
        announceWrites(written)
        return SongChanges(written = (written + shownAgain).distinct().size, failed = failed, notAllowed = notAllowed)
    }

    override suspend fun changesFromMellow(trackIds: Collection<String>): ChangesFromMellow {
        val edited = withContext(Dispatchers.IO) { edits.all() }
        val written = HashSet<String>()
        val kept = HashSet<String>()
        val pictures = HashSet<String>()
        for (text in trackIds) {
            val id = text.toLongOrNull() ?: continue
            val parts = songFiles.changedParts(id)
            if (parts.isNotEmpty()) written += text
            if (Mp3Tag.Part.Picture in parts) pictures += text
            val edit = edited[id] ?: continue
            kept += text
            if (edit.picture != null) pictures += text
        }
        return ChangesFromMellow(written, kept, pictures)
    }

    override suspend fun songsAwaitingWrite(): AwaitingWrite {
        if (!canWriteToSongs) return AwaitingWrite()
        return withContext(Dispatchers.IO) {
            AwaitingWrite(
                editsToMove = editsToMove().keys.map(Long::toString),
                unfinished = songFiles.unfinished().map(Long::toString),
            )
        }
    }

    override suspend fun writeAwaited(): SongChanges {
        if (!canWriteToSongs) return SongChanges()
        // First whatever was cut short: those songs may not be whole.
        val finished = ArrayList<Long>()
        var failed = 0
        var notAllowed = false
        for (id in withContext(Dispatchers.IO) { songFiles.unfinished() }) {
            when (songFiles.finish(id)) {
                SongFiles.Outcome.Written -> finished += id
                SongFiles.Outcome.NotAllowed -> notAllowed = true
                else -> failed++
            }
        }
        // Then the names and covers Mellow has been keeping to itself.
        val waiting = withContext(Dispatchers.IO) { editsToMove() }
        val batch = writeEach(waiting.keys.toList()) { id ->
            val edit = waiting[id] ?: return@writeEach null
            val jpeg = edit.picture?.let { pictures.bytes(it) ?: return@writeEach null }
            Mp3Tag.Changes(edit.title, edit.artist, edit.album, jpeg)
        }
        withContext(Dispatchers.IO) {
            // In the file now, so no longer kept over it.
            if (batch.written.isNotEmpty()) edits.undo(batch.written)
            noteKeptInMellow(add = batch.unfit, drop = batch.written)
        }
        announceWrites(batch.written + finished)
        val moved = batch.report()
        return moved.copy(
            written = moved.written + finished.size,
            failed = moved.failed + failed,
            notAllowed = moved.notAllowed || notAllowed,
        )
    }

    /** What Mellow keeps over a song's file that could be in the file instead: every edit, but those known not to fit one. */
    private fun editsToMove(): Map<Long, TrackEdits.Edit> {
        val staying = keptInMellow()
        return edits.all().filterKeys { it !in staying }
    }

    /** What came of writing to several songs, before it is known what that means for what Mellow keeps. */
    private class Batch {
        val written = ArrayList<Long>()

        /** Songs of a kind that cannot be written to. */
        val unfit = ArrayList<Long>()
        var failed = 0
        var notAllowed = false

        fun report() = SongChanges(written = written.size, keptInMellow = unfit.size, failed = failed, notAllowed = notAllowed)
    }

    /** Writes into each of these songs what [changes] gives for it. A null from it counts as a failure for that song. */
    private suspend fun writeEach(ids: List<Long>, changes: (Long) -> Mp3Tag.Changes?): Batch {
        val batch = Batch()
        for (id in ids) {
            val wanted = withContext(Dispatchers.IO) { changes(id) }
            if (wanted == null) {
                batch.failed++
                continue
            }
            when (songFiles.write(id, wanted)) {
                SongFiles.Outcome.Written -> batch.written += id
                SongFiles.Outcome.NotSupported -> batch.unfit += id
                SongFiles.Outcome.NotAllowed -> batch.notAllowed = true
                SongFiles.Outcome.CannotRead, SongFiles.Outcome.Failed -> batch.failed++
            }
        }
        return batch
    }

    /**
     * After songs were written to: has Android read those files again, so that its index
     * and the pictures it serves are the new ones, and then says the library changed.
     */
    private suspend fun announceWrites(written: List<Long>) {
        if (written.isNotEmpty()) {
            pictureWrites++
            val paths = withContext(Dispatchers.IO) {
                written.chunked(IdChunk).flatMap { chunk ->
                    resolver.query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        arrayOf(PathColumn),
                        "${MediaStore.Audio.Media._ID} IN (${chunk.joinToString(",") { "?" }})",
                        chunk.map(Long::toString).toTypedArray(),
                        null,
                    )?.use { cursor ->
                        buildList { while (cursor.moveToNext()) cursor.getString(0)?.let(::add) }
                    }.orEmpty()
                }
            }
            if (paths.isNotEmpty()) {
                withTimeoutOrNull(RescanPatienceMs) {
                    suspendCancellableCoroutine { done ->
                        val left = AtomicInteger(paths.size)
                        MediaScannerConnection.scanFile(context, paths.toTypedArray(), null) { _, _ ->
                            if (left.decrementAndGet() <= 0 && done.isActive) done.resume(Unit)
                        }
                    }
                }
            }
        }
        _revision.update { it + 1 }
    }

    /** Songs whose changes stay in Mellow for good, because their kind of file cannot take them. */
    private fun keptInMellow(): Set<Long> =
        notes.getStringSet(KeptInMellow, emptySet()).orEmpty().mapNotNullTo(HashSet(), String::toLongOrNull)

    /**
     * The picture at [pictureUri], ready to store or to write into songs: one picked from
     * the phone, or the cover a song wears. A song's own cover is read out of its file
     * whole; Android serves it at the song's address too, but only as a small thumbnail,
     * and that is what the other songs would be given. Null if it cannot be read as an image.
     */
    private suspend fun readPicture(pictureUri: String): ByteArray? {
        val uri = Uri.parse(pictureUri)
        val ownCoverOf = uri.pathSegments
            .takeIf { pictureUri.startsWith("$TrackUri/") && uri.lastPathSegment == "albumart" && it.size >= 2 }
            ?.let { it[it.size - 2].toLongOrNull() }
        if (ownCoverOf != null) {
            val inFile = songFiles.picture(ownCoverOf)?.let { withContext(Dispatchers.IO) { pictures.read(it) } }
            if (inFile != null) return inFile
        }
        return withContext(Dispatchers.IO) { pictures.read(uri) }
    }

    private fun noteKeptInMellow(add: Collection<Long> = emptyList(), drop: Collection<Long> = emptyList()) {
        if (add.isEmpty() && drop.isEmpty()) return
        val now = keptInMellow() + add - drop.toSet()
        notes.edit().putStringSet(KeptInMellow, now.mapTo(HashSet(), Long::toString)).apply()
    }

    override suspend fun setCoverPicture(trackIds: Collection<String>, pictureUri: String): Boolean {
        val ids = trackIds.mapNotNull(String::toLongOrNull)
        if (ids.isEmpty()) return false
        val jpeg = readPicture(pictureUri) ?: return false
        val stored = withContext(Dispatchers.IO) {
            edits.setPicture(ids, jpeg)
            true
        }
        if (stored) _revision.update { it + 1 }
        return stored
    }

    override suspend fun clearCoverPicture(trackIds: Collection<String>) {
        withContext(Dispatchers.IO) { edits.usePicture(trackIds.mapNotNull(String::toLongOrNull), name = null) }
        _revision.update { it + 1 }
    }

    override suspend fun undoEdits(trackIds: Collection<String>) {
        withContext(Dispatchers.IO) { edits.undo(trackIds.mapNotNull(String::toLongOrNull)) }
        _revision.update { it + 1 }
    }

    /** Called when edits were written from outside, by a backup being imported. */
    internal fun editsChanged() {
        _revision.update { it + 1 }
    }

    private fun List<Track>.edited(): List<Track> = if (edits.all().isEmpty()) this else map(edits::apply)

    override suspend fun tracksTitled(title: String): List<Track> = read(emptyList()) {
        queryTracks(
            selection = "$MusicOnly AND ${MediaStore.Audio.Media.TITLE} = ? COLLATE NOCASE",
            args = arrayOf(title),
            order = MediaStore.Audio.Media._ID,
            offset = 0,
            limit = SameTitleCap,
        )
    }

    override val canDeleteFiles: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    override fun deleteRequest(trackIds: Collection<String>): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val files = trackIds.mapNotNull(String::toLongOrNull)
            .map { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) }
        if (files.isEmpty()) return null
        return MediaStore.createDeleteRequest(resolver, files).intentSender
    }

    override suspend fun search(query: String, limit: Int): SearchResults {
        val words = query.trim()
        if (words.isEmpty()) return SearchResults.None
        val pattern = "%$words%"
        return read(SearchResults.None) {
            // More than asked for is read so that the best matches can be put first below;
            // the index can only say whether a row matches, not how well.
            val byFile = queryTracks(
                selection = "$MusicOnly AND (${MediaStore.Audio.Media.TITLE} LIKE ? OR " +
                    "${MediaStore.Audio.Media.ARTIST} LIKE ? OR ${MediaStore.Audio.Media.ALBUM} LIKE ?)",
                args = arrayOf(pattern, pattern, pattern),
                order = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
                offset = 0,
                limit = limit * RankingHeadroom,
            )
            // The index only knows what the files say. Songs the user has renamed are
            // looked for under their new names as well.
            val renamed = edits.matching(words).map(Long::toString).filterNot { id -> byFile.any { it.id == id } }
            val tracks = (byFile + filedById(renamed)).edited()
            val albums = queryAlbums(
                selection = "${MediaStore.Audio.Albums.ALBUM} LIKE ? OR ${MediaStore.Audio.Albums.ARTIST} LIKE ?",
                args = arrayOf(pattern, pattern),
                offset = 0,
                limit = limit * RankingHeadroom,
            )
            SearchResults(
                tracks = tracks.sortedBy { closeness(words, it.title, it.artist) }.take(limit),
                albums = albums.sortedBy { closeness(words, it.title, it.artist) }.take(limit),
            )
        }
    }

    /** Lower is closer: the name starts with the words, a word in it does, it contains them, or only the artist does. */
    private fun closeness(words: String, name: String, artist: String): Int = when {
        name.startsWith(words, ignoreCase = true) -> 0
        name.contains(" $words", ignoreCase = true) -> 1
        name.contains(words, ignoreCase = true) -> 2
        artist.contains(words, ignoreCase = true) -> 3
        else -> 4
    }

    private fun queryTracks(selection: String, args: Array<String>?, order: String, offset: Int, limit: Int): List<Track> =
        query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, TrackColumns, selection, args, order, offset, limit) { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val duration = cursor.getColumnIndexOrThrow(DurationColumn)
            val modified = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            buildList(cursor.count) {
                while (cursor.moveToNext()) {
                    val trackId = cursor.getLong(id)
                    val trackAlbumId = cursor.getLong(albumId)
                    add(
                        Track(
                            id = trackId.toString(),
                            title = cursor.getString(title).named("Untitled"),
                            artist = cursor.getString(artist).named("Unknown artist"),
                            album = cursor.getString(album).named("Unknown album"),
                            albumId = trackAlbumId.toString(),
                            durationMs = cursor.getLong(duration),
                            audioUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, trackId).toString(),
                            artwork = trackArt(trackId, cursor.getLong(modified)),
                        ),
                    )
                }
            }
        }

    private fun queryAlbums(selection: String?, args: Array<String>?, offset: Int, limit: Int): List<Album> =
        query(
            MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
            AlbumColumns,
            selection,
            args,
            "${MediaStore.Audio.Albums.ALBUM} COLLATE NOCASE ASC",
            offset,
            limit,
        ) { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums._ID)
            val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums.ALBUM)
            val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums.ARTIST)
            val count = cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums.NUMBER_OF_SONGS)
            buildList(cursor.count) {
                while (cursor.moveToNext()) {
                    val albumId = cursor.getLong(id)
                    add(
                        Album(
                            id = albumId.toString(),
                            title = cursor.getString(title).named("Unknown album"),
                            artist = cursor.getString(artist).named("Unknown artist"),
                            artwork = albumArt(albumId),
                            trackCount = cursor.getInt(count),
                        ),
                    )
                }
            }
        }

    /**
     * One page of a query. Android 11 and later take the window as arguments; before
     * that the only way to ask for one was to append it to the sort order.
     */
    private inline fun <T> query(
        uri: Uri,
        columns: Array<String>,
        selection: String?,
        args: Array<String>?,
        order: String,
        offset: Int,
        limit: Int,
        rows: (Cursor) -> List<T>,
    ): List<T> {
        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val window = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, order)
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
            }
            resolver.query(uri, columns, window, null)
        } else {
            resolver.query(uri, columns, selection, args, "$order LIMIT $limit OFFSET $offset")
        }
        return cursor?.use(rows) ?: emptyList()
    }

    /** Runs a query off the main thread. Without permission there is nothing to read, so [nothing] is the answer. */
    private suspend fun <T> read(nothing: T, block: () -> T): T {
        if (!_access.value) return nothing
        return withContext(Dispatchers.IO) {
            try {
                block()
            } catch (_: SecurityException) {
                // The permission was taken away between the check and the query.
                nothing
            }
        }
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, LibraryAccess.permission) == PackageManager.PERMISSION_GRANTED

    /** Files with no tag come back blank or as MediaStore's "<unknown>". */
    private fun String?.named(fallback: String): String =
        if (isNullOrBlank() || this == MediaStore.UNKNOWN_STRING) fallback else this

    /**
     * Where Android serves a track's own picture from: the one inside its file. The time
     * the file last changed rides along. Android ignores it, but it makes the address a
     * different one after the file is rewritten, and an address is what images are cached by.
     */
    private fun trackArt(trackId: Long, modified: Long): Artwork =
        Artwork(uri = "$TrackUri/$trackId/albumart?m=$modified")

    private fun albumArt(albumId: Long): Artwork {
        val address = ContentUris.withAppendedId(AlbumArtUri, albumId).toString()
        val writes = pictureWrites
        return Artwork(uri = if (writes == 0) address else "$address?w=$writes")
    }

    private companion object {
        const val DurationColumn = "duration"

        val TrackColumns = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            DurationColumn,
            MediaStore.Audio.Media.DATE_MODIFIED,
        )
        val AlbumColumns = arrayOf(
            MediaStore.Audio.Albums._ID,
            MediaStore.Audio.Albums.ALBUM,
            MediaStore.Audio.Albums.ARTIST,
            MediaStore.Audio.Albums.NUMBER_OF_SONGS,
        )

        /** Leaves out ringtones, alarms, notification sounds and recordings. */
        const val MusicOnly = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

        /** The id breaks ties, so paging through files added in the same second is stable. */
        const val NewestFirst = "${MediaStore.Audio.Media.DATE_ADDED} DESC, ${MediaStore.Audio.Media._ID} DESC"
        const val AlbumOrder = "${MediaStore.Audio.Media.TRACK} ASC, ${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        /** The system serves an album's cover from here, extracting it from the files on first use. */
        val AlbumArtUri: Uri = Uri.parse("content://media/external/audio/albumart")

        /** A track's own picture is served from under its own address. */
        const val TrackUri = "content://media/external/audio/media"

        /** Where a file is on the phone. Only used to ask Android to read it again. */
        const val PathColumn = "_data"

        const val KeptInMellow = "kept-in-mellow"

        const val AlbumTrackCap = 500
        const val IdChunk = 400
        const val SameTitleCap = 40
        const val RescanPatienceMs = 8_000L
        const val RankingHeadroom = 3
        const val ChangeQuietMs = 600L
    }
}
