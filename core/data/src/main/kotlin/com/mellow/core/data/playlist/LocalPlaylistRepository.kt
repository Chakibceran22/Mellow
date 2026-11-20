package com.mellow.core.data.playlist

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import com.mellow.core.data.BackupContents
import com.mellow.core.data.ExportOutcome
import com.mellow.core.data.ImportOutcome
import com.mellow.core.data.PlaylistRepository
import com.mellow.core.data.device.DeviceLibraryRepository
import com.mellow.core.model.Artwork
import com.mellow.core.model.Playlist
import com.mellow.core.model.PlaylistCover
import com.mellow.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.abs

/**
 * Playlists kept in a small database in the app's private storage.
 *
 * Three things are stored: each playlist's name, which tracks are in it (by the id the
 * phone's media index gives them, in order), and what the user chose for its cover.
 * Titles, artists and album art are not copied; they are read from the index through
 * [library] whenever a playlist is shown, so a file that is retagged shows its new name
 * here too. A cover picture the user picked is the one exception: it is copied in, because
 * the original belongs to the gallery and may be moved or deleted.
 *
 * All of this lives inside the app and goes when the app is removed, which is what
 * [exportTo] and [importFrom] are for. The backup also carries the user's edits to songs,
 * which are the library's to keep but have the same problem; that is why this takes the
 * library as the class it is rather than as its interface.
 *
 * Plain SQLite rather than an ORM: two tables and a handful of statements do not justify
 * an annotation processor in the build.
 */
class LocalPlaylistRepository(
    context: Context,
    private val library: DeviceLibraryRepository,
) : PlaylistRepository {

    private val context = context.applicationContext
    private val database = PlaylistDatabase(this.context)
    private val pictures = library.pictures

    private val _revision = MutableStateFlow(0)
    override val revision: StateFlow<Int> = _revision.asStateFlow()

    // ── Reading ─────────────────────────────────────────────────────────────

    override suspend fun all(): List<Playlist> {
        recheckMissing()
        val rows = withContext(Dispatchers.IO) { readRows(tracksEach = CoverCandidates) }
        val tracks = library.tracksById(rows.flatMap { it.trackIds + listOfNotNull(it.coverTrackId) }).associateBy(Track::id)
        return rows.map { row ->
            val chosen = row.coverTrackId?.let(tracks::get)
            Playlist(
                id = row.id.toString(),
                name = row.name,
                trackCount = row.trackCount,
                covers = when {
                    row.picture != null -> listOf(Artwork(pictures.address(row.picture)))
                    chosen != null -> listOf(chosen.artwork)
                    else -> row.trackIds.mapNotNull { tracks[it]?.artwork }.distinct().take(CoversShown)
                },
                // A chosen song that has since left the phone falls back to automatic by itself.
                cover = when {
                    row.picture != null -> PlaylistCover.Picture
                    chosen != null -> PlaylistCover.FromTrack(chosen.id)
                    else -> PlaylistCover.Automatic
                },
            )
        }
    }

    override suspend fun tracks(playlistId: String): List<Track> = tracks(playlistId, offset = 0, limit = Int.MAX_VALUE)

    override suspend fun tracks(playlistId: String, offset: Int, limit: Int): List<Track> {
        // Normally one pass. A second is only needed when a track in the page has left
        // the phone: it is marked, and the page is read again without it, so a page is
        // never short unless the playlist really ends there.
        repeat(MissingPasses) {
            val ids = withContext(Dispatchers.IO) { readTrackIds(database.readableDatabase, playlistId, limit, offset) }
            val found = library.tracksById(ids).associateBy(Track::id)
            val gone = ids.filterNot(found::containsKey)
            if (gone.isEmpty() || !library.access.value) return ids.mapNotNull(found::get)
            markMissing(gone, missing = true)
        }
        return emptyList()
    }

    /**
     * A track whose file cannot be found is kept, but set aside: it is not listed or
     * counted. It is not deleted, because "cannot be found" is sometimes temporary (a
     * memory card taken out, access switched off). Each time playlists are read, the ones
     * set aside are looked for again, and any that are back return to their place.
     */
    private suspend fun recheckMissing() {
        if (!library.access.value) return
        val setAside = withContext(Dispatchers.IO) {
            val ids = ArrayList<String>()
            database.readableDatabase.rawQuery("SELECT DISTINCT track_id FROM playlist_track WHERE missing = 1", null).use { cursor ->
                while (cursor.moveToNext()) ids += cursor.getLong(0).toString()
            }
            ids
        }
        if (setAside.isEmpty()) return
        val back = library.tracksById(setAside).map(Track::id)
        if (back.isNotEmpty()) markMissing(back, missing = false)
    }

    /** Quiet on purpose: nobody watching needs telling, the caller is already re-reading. */
    private suspend fun markMissing(trackIds: List<String>, missing: Boolean) = withContext(Dispatchers.IO) {
        val db = database.writableDatabase
        for (chunk in trackIds.mapNotNull(String::toLongOrNull).chunked(StatementChunk)) {
            db.execSQL("UPDATE playlist_track SET missing = ${if (missing) 1 else 0} WHERE track_id IN (${chunk.joinToString(",")})")
        }
    }

    // ── Changing ────────────────────────────────────────────────────────────

    override suspend fun create(name: String): Playlist {
        val id = write { db -> insertPlaylist(db, name) }
        return Playlist(id = id.toString(), name = name, trackCount = 0, covers = emptyList())
    }

    override suspend fun rename(playlistId: String, name: String) {
        write { db ->
            db.update("playlist", ContentValues().apply { put("name", name) }, "id = ?", arrayOf(playlistId))
        }
    }

    override suspend fun delete(playlistId: String) {
        write { db ->
            val picture = pictureOf(db, playlistId)
            // Its rows in playlist_track go with it: the table is declared ON DELETE CASCADE.
            db.delete("playlist", "id = ?", arrayOf(playlistId))
            pictures.delete(picture)
        }
    }

    override suspend fun add(playlistId: String, trackIds: Collection<String>): Int =
        write { db -> insertTracks(db, playlistId, trackIds) }

    override suspend fun remove(playlistId: String, trackIds: Collection<String>) {
        write { db ->
            for (chunk in trackIds.mapNotNull(String::toLongOrNull).chunked(StatementChunk)) {
                val gone = chunk.joinToString(",")
                db.delete("playlist_track", "playlist_id = ? AND track_id IN ($gone)", arrayOf(playlistId))
                // A song that is no longer in the playlist cannot be its cover.
                db.execSQL("UPDATE playlist SET cover_track_id = NULL WHERE id = ? AND cover_track_id IN ($gone)", arrayOf(playlistId))
            }
        }
    }

    override suspend fun forget(trackIds: Collection<String>) {
        write { db ->
            for (chunk in trackIds.mapNotNull(String::toLongOrNull).chunked(StatementChunk)) {
                val gone = chunk.joinToString(",")
                db.delete("playlist_track", "track_id IN ($gone)", null)
                db.execSQL("UPDATE playlist SET cover_track_id = NULL WHERE cover_track_id IN ($gone)")
            }
        }
    }

    // ── Covers ──────────────────────────────────────────────────────────────

    override suspend fun setCover(playlistId: String, cover: PlaylistCover) {
        val trackId = (cover as? PlaylistCover.FromTrack)?.trackId?.toLongOrNull()
        // A picture has to come with its file; that goes through setCoverPicture.
        if (cover is PlaylistCover.Picture || (cover is PlaylistCover.FromTrack && trackId == null)) return
        write { db ->
            val old = pictureOf(db, playlistId)
            db.update(
                "playlist",
                ContentValues().apply {
                    if (trackId != null) put("cover_track_id", trackId) else putNull("cover_track_id")
                    putNull("cover_picture")
                },
                "id = ?",
                arrayOf(playlistId),
            )
            pictures.delete(old)
        }
    }

    override suspend fun setCoverPicture(playlistId: String, pictureUri: String): Boolean {
        val jpeg = withContext(Dispatchers.IO) { pictures.read(Uri.parse(pictureUri)) } ?: return false
        storePicture(playlistId, jpeg)
        return true
    }

    /** Saves [jpeg] as the playlist's cover and drops the picture it replaces. */
    private suspend fun storePicture(playlistId: String, jpeg: ByteArray) {
        write { db ->
            val old = pictureOf(db, playlistId)
            val name = pictures.store(jpeg, PictureKind)
            db.update(
                "playlist",
                ContentValues().apply {
                    put("cover_picture", name)
                    putNull("cover_track_id")
                },
                "id = ?",
                arrayOf(playlistId),
            )
            pictures.delete(old)
        }
    }

    // ── Backup ──────────────────────────────────────────────────────────────

    override suspend fun backupContents(): BackupContents = withContext(Dispatchers.IO) {
        BackupContents(playlists = readRows(tracksEach = 0).size, songs = library.edits.all().size)
    }

    override suspend fun exportTo(destinationUri: String): ExportOutcome {
        // Without the music index every playlist would be written out empty, and the file
        // would look like a good backup while holding nothing.
        if (!library.access.value) return ExportOutcome.NeedsAccess

        val rows = withContext(Dispatchers.IO) { readRows(tracksEach = null) }
        val edits = withContext(Dispatchers.IO) { library.edits.all() }
        // A backup describes each track by what its file says, not by what it has been
        // renamed to here, because the file is what will still be there to match against.
        val filed = library.tracksAsFiled(rows.flatMap { it.trackIds } + edits.keys.map(Long::toString)).associateBy(Track::id)

        return withContext(Dispatchers.IO) {
            val backup = Backup(
                playlists = rows.map { row ->
                    val present = row.trackIds.mapNotNull(filed::get)
                    SavedPlaylist(
                        name = row.name,
                        coverTrack = present.indexOfFirst { it.id == row.coverTrackId },
                        coverPicture = row.picture?.let(pictures::bytes),
                        tracks = present.map { it.saved() },
                    )
                },
                // Edits to a song that has left the phone cannot be described, so are left out.
                songs = edits.mapNotNull { (id, edit) ->
                    filed[id.toString()]?.let { SavedSong(it.saved(), edit.title, edit.artist, edit.album, edit.picture) }
                },
                pictures = edits.values.mapNotNull { it.picture }.distinct()
                    .mapNotNull { name -> pictures.bytes(name)?.let { name to it } }.toMap(),
            )
            try {
                val out = context.contentResolver.openOutputStream(Uri.parse(destinationUri), "wt")
                    ?: return@withContext ExportOutcome.Failed
                out.use { it.write(PlaylistBackupFile.encode(backup).toByteArray()) }
                ExportOutcome.Saved(playlists = backup.playlists.size, songs = backup.songs.size)
            } catch (_: IOException) {
                ExportOutcome.Failed
            } catch (_: SecurityException) {
                ExportOutcome.Failed
            }
        }
    }

    override suspend fun importFrom(sourceUri: String): ImportOutcome {
        // What is in the file has to be looked for among the music on this phone.
        if (!library.access.value) return ImportOutcome.NeedsAccess

        val text = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(Uri.parse(sourceUri))?.use { it.readBytes().decodeToString() }
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        } ?: return ImportOutcome.Unreadable
        val backup = PlaylistBackupFile.decode(text) ?: return ImportOutcome.NotABackup

        val existing = withContext(Dispatchers.IO) { readRows(tracksEach = 0) }.associate { it.name to it.id.toString() }
        // Every saved id is looked up in one go; most of them will still be right.
        val savedIds = backup.playlists.flatMap { playlist -> playlist.tracks.map { it.id } } + backup.songs.map { it.track.id }
        val sameIds = library.tracksAsFiled(savedIds).associateBy(Track::id)

        var added = 0
        var missing = 0
        // Last first: each playlist goes to the top as it is filled, so working backwards
        // leaves them in the order the file lists them.
        for (playlist in backup.playlists.asReversed()) {
            val found = playlist.tracks.map { locate(it, sameIds) }
            missing += found.count { it == null }

            val alreadyHere = existing[playlist.name]
            val id = alreadyHere ?: create(playlist.name).id
            added += add(id, found.filterNotNull())

            // A playlist that was already here keeps the cover it has now.
            if (alreadyHere == null) {
                val coverTrack = found.getOrNull(playlist.coverTrack)
                when {
                    playlist.coverPicture != null -> storePicture(id, playlist.coverPicture)
                    coverTrack != null -> setCover(id, PlaylistCover.FromTrack(coverTrack))
                }
            }
        }

        // The edits to songs. Each picture is stored once, then shared by the songs that use it.
        var songs = 0
        val located = backup.songs.map { it to locate(it.track, sameIds)?.toLongOrNull() }
        withContext(Dispatchers.IO) {
            val storedPictures = HashMap<String, String>()
            for ((song, id) in located) {
                if (id == null) continue
                library.edits.rename(id, song.title, song.artist, song.album)
                val jpeg = song.picture?.let(backup.pictures::get)
                if (song.picture != null && jpeg != null) {
                    val name = storedPictures.getOrPut(song.picture) { pictures.store(jpeg, SongPictureKind) }
                    library.edits.usePicture(listOf(id), name)
                }
                songs++
            }
        }
        if (songs > 0) library.editsChanged()

        return ImportOutcome.Restored(playlists = backup.playlists.size, tracks = added, missing = missing, songs = songs)
    }

    private fun Track.saved() = SavedTrack(id, title, artist, album, durationMs)

    /**
     * Finds the track a backup describes among the music on this phone, and returns its
     * id here, or null if it is not here.
     *
     * On the phone the backup was made on, the saved id still points at the same file;
     * that is checked first, and believed only if the title agrees. Anywhere else, or
     * after the phone has re-indexed its music, the track is found by what it is: same
     * title, then whichever candidate agrees most on artist, album and length. All of it
     * compares what the files say, never what a song has been renamed to in Mellow.
     */
    private suspend fun locate(saved: SavedTrack, sameIds: Map<String, Track>): String? {
        val sameId = sameIds[saved.id]
        if (sameId != null && sameId.title.equals(saved.title, ignoreCase = true)) return sameId.id

        val candidates = library.tracksTitled(saved.title)
        return candidates.maxByOrNull { likeness(it, saved) }
            // With several songs of the same name, a wrong guess is worse than a gap.
            ?.takeIf { candidates.size == 1 || likeness(it, saved) >= ConfidentLikeness }
            ?.id
    }

    private fun likeness(track: Track, saved: SavedTrack): Int {
        var score = 0
        if (track.artist.equals(saved.artist, ignoreCase = true)) score += 2
        if (track.album.equals(saved.album, ignoreCase = true)) score += 2
        if (abs(track.durationMs - saved.durationMs) <= LengthToleranceMs) score += 1
        return score
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    /**
     * Every playlist, most recently changed first.
     *
     * @param tracksEach how many of each playlist's track ids to read as well: null for all.
     */
    private fun readRows(tracksEach: Int?): List<Row> {
        val db = database.readableDatabase
        val rows = ArrayList<Row>()
        db.rawQuery(
            "SELECT p.id, p.name, p.cover_track_id, p.cover_picture, " +
                "(SELECT COUNT(*) FROM playlist_track t WHERE t.playlist_id = p.id AND t.missing = 0) " +
                "FROM playlist p ORDER BY p.updated_at DESC, p.id DESC",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += Row(
                    id = cursor.getLong(0),
                    name = cursor.getString(1),
                    coverTrackId = if (cursor.isNull(2)) null else cursor.getLong(2).toString(),
                    picture = cursor.getString(3)?.takeIf { pictures.file(it).exists() },
                    trackCount = cursor.getInt(4),
                )
            }
        }
        if (tracksEach != 0) {
            for (row in rows) row.trackIds = readTrackIds(db, row.id.toString(), tracksEach ?: Int.MAX_VALUE)
        }
        return rows
    }

    /** A window of a playlist's track ids, in order, leaving out any set aside as missing. */
    private fun readTrackIds(db: SQLiteDatabase, playlistId: String, limit: Int, offset: Int = 0): List<String> {
        val ids = ArrayList<String>()
        db.rawQuery(
            // SQLite reads a negative limit as "no limit".
            "SELECT track_id FROM playlist_track WHERE playlist_id = ? AND missing = 0 ORDER BY position " +
                "LIMIT ${if (limit == Int.MAX_VALUE) -1 else limit} OFFSET $offset",
            arrayOf(playlistId),
        ).use { cursor ->
            while (cursor.moveToNext()) ids += cursor.getLong(0).toString()
        }
        return ids
    }

    /** The name of the picture stored as a playlist's cover, if it has one. */
    private fun pictureOf(db: SQLiteDatabase, playlistId: String): String? =
        db.rawQuery("SELECT cover_picture FROM playlist WHERE id = ?", arrayOf(playlistId)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun insertPlaylist(db: SQLiteDatabase, name: String): Long {
        val now = System.currentTimeMillis()
        return db.insertOrThrow(
            "playlist",
            null,
            ContentValues().apply {
                put("name", name)
                put("created_at", now)
                put("updated_at", now)
            },
        )
    }

    /** Adds tracks to the end, skipping any already in the playlist. Returns how many went in. */
    private fun insertTracks(db: SQLiteDatabase, playlistId: String, trackIds: Collection<String>): Int {
        val already = HashSet<Long>()
        var position = 0L
        db.rawQuery(
            "SELECT track_id, position FROM playlist_track WHERE playlist_id = ?",
            arrayOf(playlistId),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                already += cursor.getLong(0)
                position = maxOf(position, cursor.getLong(1) + 1)
            }
        }
        var added = 0
        for (trackId in trackIds.mapNotNull(String::toLongOrNull)) {
            if (!already.add(trackId)) continue
            db.insertOrThrow(
                "playlist_track",
                null,
                ContentValues().apply {
                    put("playlist_id", playlistId.toLong())
                    put("track_id", trackId)
                    put("position", position++)
                },
            )
            added++
        }
        // Brings the playlist to the top of the list: it is the one just worked on.
        db.update(
            "playlist",
            ContentValues().apply { put("updated_at", System.currentTimeMillis()) },
            "id = ?",
            arrayOf(playlistId),
        )
        return added
    }

    /** One change, all or nothing, off the main thread; then everyone watching is told. */
    private suspend fun <T> write(change: (SQLiteDatabase) -> T): T {
        val result = withContext(Dispatchers.IO) {
            val db = database.writableDatabase
            db.beginTransaction()
            try {
                change(db).also { db.setTransactionSuccessful() }
            } finally {
                db.endTransaction()
            }
        }
        _revision.update { it + 1 }
        return result
    }

    private class Row(
        val id: Long,
        val name: String,
        val coverTrackId: String?,
        /** The name of the chosen picture, if there is one and its file is still there. */
        val picture: String?,
        val trackCount: Int,
    ) {
        var trackIds: List<String> = emptyList()
    }

    private companion object {
        /** A cover is made of four album covers; a few spare are read in case tracks share an album. */
        const val CoversShown = 4
        const val CoverCandidates = 12
        const val StatementChunk = 400
        const val MissingPasses = 4

        const val PictureKind = "playlist"

        /** Must match the kind the library's own edits store their pictures under. */
        const val SongPictureKind = "song"

        const val LengthToleranceMs = 2_000L

        /** Agreeing on the artist or the album, plus one more thing. */
        const val ConfidentLikeness = 3
    }
}

private class PlaylistDatabase(context: Context) : SQLiteOpenHelper(context, "playlists.db", null, 3) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        // At most one of the two cover columns is set; neither means the cover is made
        // from the playlist's songs.
        db.execSQL(
            "CREATE TABLE playlist (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "name TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL, " +
                "updated_at INTEGER NOT NULL, " +
                "cover_track_id INTEGER, " +
                "cover_picture TEXT)",
        )
        // A track is in a playlist once. Position only has to increase; gaps are fine.
        db.execSQL(
            "CREATE TABLE playlist_track (" +
                "playlist_id INTEGER NOT NULL REFERENCES playlist(id) ON DELETE CASCADE, " +
                "track_id INTEGER NOT NULL, " +
                "position INTEGER NOT NULL, " +
                "missing INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY (playlist_id, track_id))",
        )
        db.execSQL("CREATE INDEX playlist_track_order ON playlist_track(playlist_id, position)")
        db.execSQL("CREATE INDEX playlist_track_track ON playlist_track(track_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 2: a playlist can have a cover of the user's choosing. Existing playlists keep
        // everything they had and start with the automatic cover.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE playlist ADD COLUMN cover_track_id INTEGER")
            db.execSQL("ALTER TABLE playlist ADD COLUMN cover_picture TEXT")
        }
        // 3: a track whose file has gone can be set aside instead of being listed, which
        // is what lets a playlist be read a page at a time and counted exactly.
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE playlist_track ADD COLUMN missing INTEGER NOT NULL DEFAULT 0")
        }
    }
}
