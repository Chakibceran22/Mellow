package com.mellow.core.model

/**
 * Cover art for a track or an album.
 *
 * @property uri where the image can be read on this device. The image is decoded only when
 *   something shows it, and there may turn out to be none: plenty of audio files carry no
 *   cover. Whatever displays it must cope with that.
 */
data class Artwork(
    val uri: String,
)
