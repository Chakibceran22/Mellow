package com.mellow.feature.settings

import androidx.compose.runtime.Immutable
import com.mellow.core.model.Appearance

@Immutable
data class SettingsUiState(
    /** The look that is chosen: light, dark, or whichever the phone is in. */
    val appearance: Appearance = Appearance.Automatic,
    /** How many playlists there are to back up. Null until counted. */
    val playlistCount: Int? = null,
    /** How many songs carry edits (a changed name or cover), which a backup also saves. */
    val editedSongCount: Int = 0,
    /** A backup is being written or read right now. */
    val working: Boolean = false,
    /** What the last save or import came to, if there has been one. */
    val result: BackupResult? = null,
)

/**
 * @property problem true when the thing asked for did not happen, so the words should
 *   stand out and say what to do about it.
 */
@Immutable
data class BackupResult(
    val message: String,
    val problem: Boolean = false,
)
