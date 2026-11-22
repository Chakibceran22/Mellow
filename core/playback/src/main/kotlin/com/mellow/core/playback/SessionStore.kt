package com.mellow.core.playback

import android.content.Context
import android.provider.MediaStore
import com.mellow.core.model.Artwork
import com.mellow.core.model.Track
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What was on, as it is written down: the tracks in the order they were to play, which of
 * them was on, and how far into it the player had got.
 *
 * @property index which of [items] was on. Always a valid position: a session with no
 *   tracks is not a session, and is never made.
 * @property source the list the tracks were put on from, as [PlaybackState.source] gives it.
 * @property sourceLabel what that list is called, as [UpNext.from] gives it.
 * @property lastPlace how far the list that was put on had got; see the controller.
 * @property finished the player had played to the end of everything and stopped. Playing
 *   again then means starting the list over, not carrying on from the end of its last track.
 */
internal data class SavedSession(
    val items: List<SavedItem>,
    val index: Int,
    val positionMs: Long,
    val source: String?,
    val sourceLabel: String?,
    val lastPlace: Int,
    val finished: Boolean,
) {
    val track: Track get() = items[index].track
}

/**
 * One track of a [SavedSession], with what the player's list knew about it.
 *
 * @property place where it stands in the list it was put on from.
 * @property queued the number the user's own queue gave it, or 0 if it was not queued by them.
 */
internal data class SavedItem(val track: Track, val place: Int, val queued: Long)

/**
 * Keeps the last [SavedSession] in the app's own storage, so that closing the app does not
 * lose the user their place: the same song is there, paused, the next time.
 *
 * Everything here reads or writes storage. Call it off the main thread.
 */
internal class SessionStore(context: Context) {

    private val resolver = context.applicationContext.contentResolver
    private val file = File(context.applicationContext.filesDir, "playback/last-session.json")

    /** The session last written, or null if there is none or it cannot be read. */
    fun read(): SavedSession? = runCatching {
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        if (json.optInt(KeyVersion) != Version) return null
        val array = json.getJSONArray(KeyItems)
        val items = ArrayList<SavedItem>(array.length())
        for (at in 0 until array.length()) {
            val item = array.getJSONObject(at)
            items += SavedItem(
                track = Track(
                    id = item.getString(KeyId),
                    title = item.optString(KeyTitle),
                    artist = item.optString(KeyArtist),
                    album = item.optString(KeyAlbum),
                    albumId = item.optString(KeyAlbumId),
                    durationMs = item.optLong(KeyDuration),
                    audioUri = item.getString(KeyAudio),
                    artwork = Artwork(uri = item.optString(KeyArtwork)),
                ),
                place = item.optInt(KeyPlace, Int.MAX_VALUE),
                queued = item.optLong(KeyQueued),
            )
        }
        val index = json.optInt(KeyIndex)
        if (index !in items.indices) return null
        SavedSession(
            items = items,
            index = index,
            positionMs = json.optLong(KeyPosition).coerceAtLeast(0L),
            source = json.optString(KeySource).ifEmpty { null },
            sourceLabel = json.optString(KeyLabel).ifEmpty { null },
            lastPlace = json.optInt(KeyLastPlace, -1),
            finished = json.optBoolean(KeyFinished),
        )
    }.getOrNull()

    /** Writes [session] down in place of the last one. Null means there is nothing on, and nothing to keep. */
    fun write(session: SavedSession?) {
        runCatching {
            if (session == null) {
                file.delete()
                return
            }
            val items = JSONArray()
            for (item in session.items) {
                items.put(
                    JSONObject()
                        .put(KeyId, item.track.id)
                        .put(KeyTitle, item.track.title)
                        .put(KeyArtist, item.track.artist)
                        .put(KeyAlbum, item.track.album)
                        .put(KeyAlbumId, item.track.albumId)
                        .put(KeyDuration, item.track.durationMs)
                        .put(KeyAudio, item.track.audioUri)
                        .put(KeyArtwork, item.track.artwork.uri)
                        .put(KeyPlace, item.place)
                        .put(KeyQueued, item.queued),
                )
            }
            val json = JSONObject()
                .put(KeyVersion, Version)
                .put(KeyIndex, session.index)
                .put(KeyPosition, session.positionMs)
                .put(KeySource, session.source.orEmpty())
                .put(KeyLabel, session.sourceLabel.orEmpty())
                .put(KeyLastPlace, session.lastPlace)
                .put(KeyFinished, session.finished)
                .put(KeyItems, items)

            // Written beside the old one and swapped in whole, so that being stopped half
            // way through leaves the last good session, never half of a new one.
            file.parentFile?.mkdirs()
            val fresh = File(file.path + ".new")
            fresh.writeText(json.toString())
            if (!fresh.renameTo(file)) fresh.delete()
        }
    }

    /**
     * [session] without the tracks that have left the phone since it was written: files
     * can be deleted while the app is closed, and a track that is gone cannot be put back
     * on. If the track that was on is one of them, the next that is still here takes its
     * place, from its start. Null when none are left.
     *
     * When the phone will not say what it holds (the permission to read music was taken
     * away), the session is given back as it is: nothing in it can be played either way.
     */
    fun stillOnPhone(session: SavedSession): SavedSession? {
        val asked = session.items.mapNotNullTo(HashSet()) { it.track.id.toLongOrNull() }
        val here = runCatching {
            val found = HashSet<String>()
            for (some in asked.chunked(AskedAtOnce)) {
                resolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media._ID),
                    // Numbers only, each one parsed above: nothing here was written by anyone.
                    "${MediaStore.Audio.Media._ID} IN (${some.joinToString(",")})",
                    null,
                    null,
                )?.use { rows -> while (rows.moveToNext()) found += rows.getLong(0).toString() } ?: return session
            }
            found
        }.getOrElse { return session }

        if (session.items.all { it.track.id in here }) return session
        val kept = ArrayList<SavedItem>()
        var index = -1
        var sameTrack = false
        session.items.forEachIndexed { at, item ->
            if (item.track.id !in here) return@forEachIndexed
            // The first survivor at or after the one that was on is the one that is on now.
            if (index < 0 && at >= session.index) {
                index = kept.size
                sameTrack = at == session.index
            }
            kept += item
        }
        if (kept.isEmpty()) return null
        return session.copy(
            items = kept,
            // Everything from the one that was on to the end has gone: stand on the last that is left.
            index = if (index < 0) kept.lastIndex else index,
            positionMs = if (sameTrack) session.positionMs else 0L,
            // It had finished on a track that is no longer here. What it stands on now has not been played.
            finished = session.finished && sameTrack,
        )
    }

    private companion object {
        /** Raised whenever what is written changes shape; an older file is then left unread. */
        const val Version = 1

        /** How many tracks the phone is asked about in one question. */
        const val AskedAtOnce = 400

        const val KeyVersion = "version"
        const val KeyIndex = "index"
        const val KeyPosition = "position"
        const val KeySource = "source"
        const val KeyLabel = "label"
        const val KeyLastPlace = "lastPlace"
        const val KeyFinished = "finished"
        const val KeyItems = "items"

        const val KeyId = "id"
        const val KeyTitle = "title"
        const val KeyArtist = "artist"
        const val KeyAlbum = "album"
        const val KeyAlbumId = "albumId"
        const val KeyDuration = "duration"
        const val KeyAudio = "audio"
        const val KeyArtwork = "artwork"
        const val KeyPlace = "place"
        const val KeyQueued = "queued"
    }
}
