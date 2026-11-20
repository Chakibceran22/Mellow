package com.mellow.feature.player

import androidx.compose.runtime.Immutable
import com.mellow.core.model.RepeatMode
import com.mellow.core.model.Track

@Immutable
data class PlayerUiState(
    /** The track loaded in the player. Null when nothing has been played yet. */
    val track: Track? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.Off,
)
