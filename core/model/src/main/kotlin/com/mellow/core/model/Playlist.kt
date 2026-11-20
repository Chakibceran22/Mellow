package com.mellow.core.model

/**
 * A list of tracks the user put together, described without its tracks. Ask the playlist
 * repository for those when they are needed.
 *
 * @property covers what to draw its cover from: the one image the user chose, or else the
 *   art of the first few albums in it. Empty for an empty playlist with no chosen cover.
 * @property cover which of those it is, so a screen can show what is currently chosen.
 */
data class Playlist(
    val id: String,
    val name: String,
    val trackCount: Int,
    val covers: List<Artwork>,
    val cover: PlaylistCover = PlaylistCover.Automatic,
)

/** Where a playlist's cover comes from. */
sealed interface PlaylistCover {

    /** Made from the songs in it, and remade as they change. */
    data object Automatic : PlaylistCover

    /** The cover of one particular song in it. */
    data class FromTrack(val trackId: String) : PlaylistCover

    /** A picture the user picked from the phone. */
    data object Picture : PlaylistCover
}
