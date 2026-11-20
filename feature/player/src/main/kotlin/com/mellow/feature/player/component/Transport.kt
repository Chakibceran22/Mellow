package com.mellow.feature.player.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.mellow.core.designsystem.component.Icon
import com.mellow.core.designsystem.component.IconButton
import com.mellow.core.designsystem.component.pressable
import com.mellow.core.designsystem.glass.GlassShape
import com.mellow.core.designsystem.glass.GlassSurface
import com.mellow.core.designsystem.icon.MellowIcons
import com.mellow.core.designsystem.theme.MellowTheme
import com.mellow.core.model.RepeatMode

/**
 * Shuffle, previous, play or pause, next, repeat. The play button is the one solid thing
 * on the glass.
 *
 * The row is built outward from the play button: the two toggles are fixed at the ends,
 * and previous and next each take an equal share of what is left and sit in the middle of
 * it. However wide the panel is, the play button is at its exact centre and the gaps on
 * either side match. (Five fixed-width buttons do not fit a 360dp phone, and when they
 * overflowed the whole row slid off-centre.)
 */
@Composable
internal fun Transport(
    isPlaying: Boolean,
    shuffle: Boolean,
    repeat: RepeatMode,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Toggle(
            icon = MellowIcons.Shuffle,
            label = "Shuffle",
            state = if (shuffle) "On" else "Off",
            active = shuffle,
            onClick = onToggleShuffle,
        )
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            IconButton(MellowIcons.Previous, "Previous track", onPrevious, iconSize = 30.dp)
        }
        PlayButton(isPlaying, onTogglePlayPause)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            IconButton(MellowIcons.Next, "Next track", onNext, iconSize = 30.dp)
        }
        Toggle(
            icon = if (repeat == RepeatMode.One) MellowIcons.RepeatOne else MellowIcons.Repeat,
            label = "Repeat",
            state = when (repeat) {
                RepeatMode.Off -> "Off"
                RepeatMode.All -> "All tracks"
                RepeatMode.One -> "This track"
            },
            active = repeat != RepeatMode.Off,
            onClick = onCycleRepeat,
        )
    }
}

@Composable
private fun PlayButton(isPlaying: Boolean, onClick: () -> Unit) {
    val colors = MellowTheme.colors
    // Green glass, like the other main buttons in the app.
    GlassSurface(
        modifier = Modifier.size(72.dp),
        shape = GlassShape.Capsule,
        style = MellowTheme.glass.accent,
        onClick = onClick,
        onClickLabel = if (isPlaying) "Pause" else "Play",
        role = Role.Button,
        pressScale = 1.07f,
        contentColor = colors.onAccent,
    ) {
        AnimatedContent(
            targetState = isPlaying,
            transitionSpec = { (scaleIn(initialScale = 0.6f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.6f) + fadeOut()) },
            label = "playPause",
        ) { playing ->
            Icon(
                icon = if (playing) MellowIcons.Pause else MellowIcons.Play,
                contentDescription = if (playing) "Pause" else "Play",
                tint = colors.onAccent,
                size = 32.dp,
            )
        }
    }
}

/** A small on/off control. When on, it takes the accent colour and gains a dot beneath it. */
@Composable
private fun Toggle(
    icon: ImageVector,
    label: String,
    state: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val colors = MellowTheme.colors
    val tint by animateColorAsState(if (active) colors.accent else colors.inkMuted, label = "toggle")
    Box(
        modifier = Modifier
            .size(44.dp)
            .semantics { stateDescription = state }
            .pressable(onClick = onClick, onClickLabel = label, role = Role.Switch, pressedScale = 0.84f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, size = 22.dp)
        if (active) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 3.dp)
                    .size(4.dp)
                    .background(colors.accent, CircleShape),
            )
        }
    }
}
