package com.mellow.core.model

/**
 * One playable piece of audio that lives on this device.
 *
 * A track is only a description: reading thousands of them opens no audio file. [audioUri]
 * is followed when the track is actually played, and not before. It always points at
 * something stored on the phone; Mellow never streams, so nothing in the app may assume a
 * network URL here.
 *
 * @property album the title of the album it belongs to, for showing next to the track.
 */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: String,
    val durationMs: Long,
    val audioUri: String,
    val artwork: Artwork,
)
