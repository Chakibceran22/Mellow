package com.mellow.core.data.playlist

import android.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Everything a backup holds. */
internal class Backup(
    val playlists: List<SavedPlaylist>,
    /** Songs the user has edited, with what they changed. */
    val songs: List<SavedSong>,
    /** Cover pictures chosen for songs, by the key [SavedSong.picture] refers to. Stored once however many songs share one. */
    val pictures: Map<String, ByteArray>,
)

/** A playlist as it is written to a backup. */
internal class SavedPlaylist(
    val name: String,
    /** Position in [tracks] of the song whose cover was chosen, or -1. */
    val coverTrack: Int,
    /** The chosen picture as a JPEG, or null. */
    val coverPicture: ByteArray?,
    val tracks: List<SavedTrack>,
)

/**
 * A track as it is written to a backup: what its file says about it. The id only means
 * something on the phone the backup was made on; the rest is what lets the same song be
 * found anywhere.
 */
internal class SavedTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

/** A song the user edited: which song, and what they changed (null where they did not). */
internal class SavedSong(
    val track: SavedTrack,
    val title: String?,
    val artist: String?,
    val album: String?,
    /** Key into [Backup.pictures], or null. */
    val picture: String?,
)

/**
 * The backup file: JSON, written to be read by people as well as by Mellow, so a file
 * found in a folder a year from now still explains itself.
 *
 * Audio is never in it. Chosen cover pictures are, as text, because they exist nowhere
 * else once the app is removed.
 *
 * Version 1 held playlists only. Version 2 adds the songs the user has edited. A version 1
 * file still reads.
 */
internal object PlaylistBackupFile {

    private const val Format = "mellow-playlists"
    private const val Version = 2

    fun encode(backup: Backup): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
        val root = JSONObject()
            .put("format", Format)
            .put("version", Version)
            .put("exportedAt", stamp)
            .put(
                "note",
                "Playlists and song edits from the Mellow music app. Import this file from Mellow's Settings. " +
                    "It lists songs; it does not contain them.",
            )

        val playlists = JSONArray()
        for (playlist in backup.playlists) {
            val tracks = JSONArray()
            for (track in playlist.tracks) tracks.put(track.toJson())
            val entry = JSONObject().put("name", playlist.name).put("tracks", tracks)
            if (playlist.coverTrack >= 0) entry.put("coverTrack", playlist.coverTrack)
            if (playlist.coverPicture != null) entry.put("coverPicture", playlist.coverPicture.toText())
            playlists.put(entry)
        }
        root.put("playlists", playlists)

        val songs = JSONArray()
        for (song in backup.songs) {
            val shownAs = JSONObject()
            song.title?.let { shownAs.put("title", it) }
            song.artist?.let { shownAs.put("artist", it) }
            song.album?.let { shownAs.put("album", it) }
            val entry = JSONObject().put("song", song.track.toJson()).put("shownAs", shownAs)
            song.picture?.let { entry.put("picture", it) }
            songs.put(entry)
        }
        root.put("songs", songs)

        val pictures = JSONObject()
        for ((key, jpeg) in backup.pictures) pictures.put(key, jpeg.toText())
        root.put("pictures", pictures)

        return root.toString(2)
    }

    /** Null when [text] is not a backup this version can read. */
    fun decode(text: String): Backup? = try {
        val root = JSONObject(text)
        if (root.optString("format") != Format || root.optInt("version", 0) !in 1..Version) {
            null
        } else {
            Backup(
                playlists = root.getJSONArray("playlists").map { entry ->
                    SavedPlaylist(
                        name = entry.getString("name"),
                        coverTrack = entry.optInt("coverTrack", -1),
                        coverPicture = entry.optString("coverPicture").takeIf { it.isNotEmpty() }?.toBytes(),
                        tracks = entry.getJSONArray("tracks").map { it.toSavedTrack() },
                    )
                },
                songs = root.optJSONArray("songs")?.map { entry ->
                    val shownAs = entry.optJSONObject("shownAs") ?: JSONObject()
                    SavedSong(
                        track = entry.getJSONObject("song").toSavedTrack(),
                        title = shownAs.optString("title").takeIf { it.isNotEmpty() },
                        artist = shownAs.optString("artist").takeIf { it.isNotEmpty() },
                        album = shownAs.optString("album").takeIf { it.isNotEmpty() },
                        picture = entry.optString("picture").takeIf { it.isNotEmpty() },
                    )
                }.orEmpty(),
                pictures = root.optJSONObject("pictures")?.let { pictures ->
                    pictures.keys().asSequence().associateWith { pictures.getString(it).toBytes() }
                }.orEmpty(),
            )
        }
    } catch (_: JSONException) {
        null
    } catch (_: IllegalArgumentException) {
        // A picture that is not valid text-encoded data.
        null
    }

    private fun SavedTrack.toJson() = JSONObject()
        .put("title", title)
        .put("artist", artist)
        .put("album", album)
        .put("durationMs", durationMs)
        .put("id", id)

    private fun JSONObject.toSavedTrack() = SavedTrack(
        id = optString("id"),
        title = getString("title"),
        artist = optString("artist"),
        album = optString("album"),
        durationMs = optLong("durationMs"),
    )

    private fun <T> JSONArray.map(read: (JSONObject) -> T): List<T> = List(length()) { read(getJSONObject(it)) }

    private fun ByteArray.toText(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.toBytes(): ByteArray = Base64.decode(this, Base64.DEFAULT)
}
