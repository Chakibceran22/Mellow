package com.mellow.core.data.device

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.mellow.core.data.local.Pictures
import com.mellow.core.model.Artwork
import com.mellow.core.model.Track

/**
 * What the user has changed about their songs: a different title, artist or album name,
 * or a cover of their own choosing.
 *
 * None of this is written into the audio files. A music file's tags belong to the file,
 * and Android does not let an app rewrite them without asking the user file by file. The
 * changes are kept here instead, against the id the phone gives each track, and laid over
 * what the file says every time a track is read. To the rest of the app an edited track
 * simply is what the user said it is.
 *
 * There are few of these compared with the size of a library, so all of them are held in
 * memory once read, and applying them to a page of tracks costs a lookup each.
 *
 * Every function here touches storage: call them off the main thread.
 */
internal class TrackEdits(context: Context, private val pictures: Pictures) {

    /** What was changed about one track. Null means "as the file says". */
    class Edit(val title: String?, val artist: String?, val album: String?, val picture: String?) {
        val isEmpty: Boolean get() = title == null && artist == null && album == null && picture == null
    }

    private val database = EditsDatabase(context.applicationContext)

    @Volatile
    private var loaded: Map<Long, Edit>? = null

    fun all(): Map<Long, Edit> = loaded ?: synchronized(this) { loaded ?: read().also { loaded = it } }

    /** [track] with the user's changes laid over it. */
    fun apply(track: Track): Track {
        val edit = all()[track.id.toLongOrNull()] ?: return track
        return track.copy(
            title = edit.title ?: track.title,
            artist = edit.artist ?: track.artist,
            album = edit.album ?: track.album,
            artwork = edit.picture?.let { Artwork(pictures.address(it)) } ?: track.artwork,
        )
    }

    /** The ids of tracks one of whose changed names contains [words]. */
    fun matching(words: String): List<Long> = all().filterValues { edit ->
        listOfNotNull(edit.title, edit.artist, edit.album).any { it.contains(words, ignoreCase = true) }
    }.keys.toList()

    /** Sets the three names of a track. A null puts that one back to what the file says. */
    fun rename(trackId: Long, title: String?, artist: String?, album: String?) = change {
        val now = all()[trackId]
        write(it, trackId, Edit(title, artist, album, now?.picture))
    }

    /** Gives every one of [trackIds] the same picture, stored once. */
    fun setPicture(trackIds: Collection<Long>, jpeg: ByteArray) {
        val name = pictures.store(jpeg, PictureKind)
        usePicture(trackIds, name)
    }

    /** Points [trackIds] at a picture that is already stored, or at none. */
    fun usePicture(trackIds: Collection<Long>, name: String?) = change { db ->
        val current = all()
        for (trackId in trackIds) {
            val now = current[trackId]
            write(db, trackId, Edit(now?.title, now?.artist, now?.album, name))
        }
    }

    /** Forgets everything that was changed about these tracks. */
    fun undo(trackIds: Collection<Long>) = change { db ->
        for (trackId in trackIds) db.delete("track_edit", "track_id = ?", arrayOf(trackId.toString()))
    }

    private fun write(db: SQLiteDatabase, trackId: Long, edit: Edit) {
        if (edit.isEmpty) {
            db.delete("track_edit", "track_id = ?", arrayOf(trackId.toString()))
        } else {
            db.insertWithOnConflict(
                "track_edit",
                null,
                ContentValues().apply {
                    put("track_id", trackId)
                    put("title", edit.title)
                    put("artist", edit.artist)
                    put("album", edit.album)
                    put("cover_picture", edit.picture)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    /** One change, all or nothing. Afterwards the copy in memory is read again and unused pictures are deleted. */
    private fun change(block: (SQLiteDatabase) -> Unit) = synchronized(this) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            block(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        val fresh = read()
        loaded = fresh
        pictures.prune(PictureKind, keep = fresh.values.mapNotNullTo(HashSet()) { it.picture })
    }

    private fun read(): Map<Long, Edit> {
        val edits = HashMap<Long, Edit>()
        database.readableDatabase.rawQuery("SELECT track_id, title, artist, album, cover_picture FROM track_edit", null).use { cursor ->
            while (cursor.moveToNext()) {
                edits[cursor.getLong(0)] = Edit(cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4))
            }
        }
        return edits
    }

    private companion object {
        const val PictureKind = "song"
    }
}

private class EditsDatabase(context: Context) : SQLiteOpenHelper(context, "song-edits.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE track_edit (" +
                "track_id INTEGER PRIMARY KEY, " +
                "title TEXT, " +
                "artist TEXT, " +
                "album TEXT, " +
                "cover_picture TEXT)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}
