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
import com.mellow.core.data.LibraryRepository
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
import kotlin.coroutines.resume

/**
 * The phone's own music, read from MediaStore.
 *
 * MediaStore is the index Android already keeps of every audio file on the device: titles,
 * artists, albums and durations, parsed once by the system when a file appears. Every
 * query here reads rows from that index and nothing else. No audio file is opened to list
 * it, however many there are; a track only carries the address ExoPlayer will open when it
 * is played. Covers work the same way: a track carries the address of its album's art, and
 * the image is decoded by whichever screen shows it, when it shows it.
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

    override suspend fun setCoverPicture(trackIds: Collection<String>, pictureUri: String): Boolean {
        val ids = trackIds.mapNotNull(String::toLongOrNull)
        if (ids.isEmpty()) return false
        val stored = withContext(Dispatchers.IO) {
            val jpeg = pictures.read(Uri.parse(pictureUri)) ?: return@withContext false
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
                            artwork = albumArt(trackAlbumId),
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

    private fun albumArt(albumId: Long): Artwork =
        Artwork(uri = ContentUris.withAppendedId(AlbumArtUri, albumId).toString())

    private companion object {
        const val DurationColumn = "duration"

        val TrackColumns = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            DurationColumn,
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

        const val AlbumTrackCap = 500
        const val IdChunk = 400
        const val SameTitleCap = 40
        const val RescanPatienceMs = 8_000L
        const val RankingHeadroom = 3
        const val ChangeQuietMs = 600L
    }
}
