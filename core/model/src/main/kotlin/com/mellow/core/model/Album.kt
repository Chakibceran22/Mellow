package com.mellow.core.model

/**
 * An album, described without its tracks. Ask the library for those when they are needed.
 */
data class Album(
    val id: String,
    val title: String,
    val artist: String,
    val artwork: Artwork,
    val trackCount: Int,
)
