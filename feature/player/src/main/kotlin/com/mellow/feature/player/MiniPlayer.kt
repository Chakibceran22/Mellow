package com.mellow.feature.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mellow.core.designsystem.component.IconButton
import com.mellow.core.designsystem.component.Text
import com.mellow.core.designsystem.glass.GlassShape
import com.mellow.core.designsystem.glass.GlassSurface
import com.mellow.core.designsystem.icon.MellowIcons
import com.mellow.core.designsystem.theme.MellowTheme
import com.mellow.feature.player.component.SpinningDisc

val MiniPlayerHeight = 66.dp

/**
 * What is playing, as a glass capsule that floats above the tab bar. Tapping it opens the
 * full player; the two buttons act without leaving the page.
 */
@Composable
fun MiniPlayer(
    state: PlayerUiState,
    onExpand: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = state.track ?: return

    GlassSurface(
        modifier = modifier.fillMaxWidth().height(MiniPlayerHeight),
        shape = GlassShape.Capsule,
        onClick = onExpand,
        onClickLabel = "Open player",
        pressScale = 1.02f,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(start = DiscInset, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SpinningDisc(track.artwork.uri, state.isPlaying, Modifier.size(MiniPlayerHeight - DiscInset * 2))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, style = MellowTheme.type.headline, maxLines = 1)
                Text(track.artist, style = MellowTheme.type.caption, color = MellowTheme.colors.inkMuted, maxLines = 1)
            }
            IconButton(
                icon = if (state.isPlaying) MellowIcons.Pause else MellowIcons.Play,
                contentDescription = if (state.isPlaying) "Pause" else "Play",
                onClick = onTogglePlayPause,
                iconSize = 26.dp,
            )
            IconButton(MellowIcons.Next, "Next track", onNext, iconSize = 24.dp)
        }
    }
}

/** Equal on every side, so the disc is concentric with the capsule's end. */
private val DiscInset = 8.dp
