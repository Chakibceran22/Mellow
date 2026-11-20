package com.mellow.feature.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mellow.core.designsystem.component.CoverArt
import com.mellow.core.designsystem.component.GlassIconButton
import com.mellow.core.designsystem.component.Scrubber
import com.mellow.core.designsystem.component.Text
import com.mellow.core.designsystem.glass.GlassScene
import com.mellow.core.designsystem.glass.GlassShape
import com.mellow.core.designsystem.glass.GlassSurface
import com.mellow.core.designsystem.icon.MellowIcons
import com.mellow.core.designsystem.light.LightField
import com.mellow.core.designsystem.theme.ContinuousCornerShape
import com.mellow.core.designsystem.theme.MellowMotion
import com.mellow.core.designsystem.theme.MellowTheme
import com.mellow.core.designsystem.theme.tabular
import com.mellow.core.model.Track
import com.mellow.feature.player.component.Transport
import kotlinx.coroutines.delay

/**
 * The full player. The room behind it is the same lit surface as the rest of the app,
 * turned up and tinted by the track's cover; the controls sit on one panel of glass.
 *
 * It is meant to stay composed while a track is loaded, parked off screen, so that opening
 * it is a slide and not a build.
 *
 * @param visible whether any of it is on screen. While false it does no work: the light
 *   holds its last frame, the glass stops redrawing and the playback position is not polled.
 */
@Composable
fun NowPlayingScreen(
    viewModel: PlayerViewModel,
    visible: Boolean,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val positionMs by produceState(initialValue = 0L, visible, viewModel) {
        if (visible) viewModel.position.collect { value = it }
    }

    GlassScene(
        modifier = modifier.fillMaxSize(),
        // Nothing but the lit room is behind the glass here, so there is nothing to bend.
        refract = false,
        active = visible,
        backdrop = { LightField(Modifier.fillMaxSize(), vividness = 1.3f, active = visible) },
    ) {
        val track = state.track ?: return@GlassScene
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp),
        ) {
            TopBar(track.album, onCollapse)
            Artwork(track, state.isPlaying, Modifier.weight(1f).fillMaxWidth())
            Titles(track)
            Spacer(Modifier.height(20.dp))
            Controls(
                state = state,
                positionMs = positionMs,
                onSeek = viewModel::seekTo,
                onTogglePlayPause = viewModel::togglePlayPause,
                onPrevious = viewModel::previous,
                onNext = viewModel::next,
                onToggleShuffle = viewModel::toggleShuffle,
                onCycleRepeat = viewModel::cycleRepeat,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TopBar(albumTitle: String, onCollapse: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(52.dp)) {
        GlassIconButton(
            icon = MellowIcons.Collapse,
            contentDescription = "Close player",
            onClick = onCollapse,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        // Kept clear of the button on the left, and of the same space on the right so it stays centred.
        Column(
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 58.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Playing from", style = MellowTheme.type.caption, color = MellowTheme.colors.inkMuted)
            Text(albumTitle, style = MellowTheme.type.headline, maxLines = 1)
        }
    }
}

/** The cover fills what space there is and settles back a little while paused. */
@Composable
private fun Artwork(track: Track, isPlaying: Boolean, modifier: Modifier = Modifier) {
    val radii = MellowTheme.radii
    val scale by animateFloatAsState(if (isPlaying) 1f else 0.84f, MellowMotion.ease(), label = "artwork")
    val shape = remember(radii) { ContinuousCornerShape(radii.panel) }

    Box(modifier.padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
        CoverArt(
            uri = track.artwork.uri,
            modifier = Modifier
                .aspectRatio(1f)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
            shape = shape,
            elevation = 30.dp,
            contentDescription = "Cover of ${track.title}",
        )
    }
}

@Composable
private fun Titles(track: Track) {
    Column(Modifier.fillMaxWidth()) {
        Text(track.title, style = MellowTheme.type.title, maxLines = 1, modifier = Modifier.semantics { heading() })
        Text(track.artist, style = MellowTheme.type.body, color = MellowTheme.colors.inkMuted, maxLines = 1)
    }
}

@Composable
private fun Controls(
    state: PlayerUiState,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
) {
    val duration = state.durationMs.coerceAtLeast(1L)

    // While the finger is down the bar follows it, not the player. After it lifts, the
    // chosen spot is held briefly so the bar does not snap back before the seek lands.
    var scrub by remember { mutableStateOf<Float?>(null) }
    var settling by remember { mutableStateOf(false) }
    LaunchedEffect(settling) {
        if (settling) {
            delay(350)
            scrub = null
            settling = false
        }
    }
    val fraction = scrub ?: (positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val shownMs = (fraction * duration).toLong()

    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = GlassShape.Continuous(MellowTheme.radii.panel),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 20.dp)) {
            Scrubber(
                fraction = fraction,
                onScrub = {
                    settling = false
                    scrub = it
                },
                onScrubFinished = {
                    scrub = it
                    onSeek((it * duration).toLong())
                    settling = true
                },
            )
            Row(Modifier.fillMaxWidth()) {
                val time = MellowTheme.type.caption.tabular
                Text(formatTime(shownMs), style = time, color = MellowTheme.colors.inkMuted)
                Spacer(Modifier.weight(1f))
                Text(formatTime(duration - shownMs), style = time, color = MellowTheme.colors.inkMuted)
            }
            Spacer(Modifier.height(10.dp))
            Transport(
                isPlaying = state.isPlaying,
                shuffle = state.shuffle,
                repeat = state.repeat,
                onTogglePlayPause = onTogglePlayPause,
                onPrevious = onPrevious,
                onNext = onNext,
                onToggleShuffle = onToggleShuffle,
                onCycleRepeat = onCycleRepeat,
            )
        }
    }
}

private fun formatTime(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
