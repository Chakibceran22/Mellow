package com.mellow.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.mellow.core.designsystem.glass.GlassShape
import com.mellow.core.designsystem.glass.GlassSurface
import com.mellow.core.designsystem.icon.MellowIcons
import com.mellow.core.designsystem.light.LocalLighting
import com.mellow.core.designsystem.theme.MellowMotion
import kotlinx.coroutines.launch

/**
 * Lets a scrolling page be pulled down from its top to reload it.
 *
 * With the page already at its top, dragging further down brings a small disc of glass
 * out from under the top edge. Its arrows wind round as the pull grows. Let go past the
 * mark and [onReload] is called; the disc then stays, turning, for as long as [reloading]
 * is true, and slips back up when it is over. Let go short of the mark and nothing happens.
 *
 * The pull takes only the drag that the page has no use for, so ordinary scrolling is
 * untouched, and pushing back up gives the pull back before the page starts to move.
 *
 * @param topInset how far below the top edge the disc comes to rest, normally the height
 *   of the status bar.
 * @param enabled false where there is nothing to reload.
 * @param content the page. It must contain something that scrolls vertically.
 */
@Composable
fun PullToReload(
    reloading: Boolean,
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val engine = LocalLighting.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val mark = with(density) { PullToTrigger.toPx() }
    val rest = with(density) { RestBelowInset.toPx() }

    // How far the page has been pulled past its top, in pixels, after resistance.
    val pull = remember { Animatable(0f) }
    val isReloading by rememberUpdatedState(reloading)
    val isEnabled by rememberUpdatedState(enabled)
    val reload by rememberUpdatedState(onReload)

    // While reloading the disc holds at the mark; when that ends, it goes back up.
    LaunchedEffect(reloading) {
        pull.animateTo(if (reloading) mark else 0f, MellowMotion.settle())
    }

    val connection = remember(mark) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Pushing back up while pulled: undo the pull before the page scrolls.
                if (source != NestedScrollSource.UserInput || available.y >= 0f || pull.value <= 0f || isReloading) return Offset.Zero
                val undone = maxOf(available.y, -pull.value / Resistance)
                scope.launch { pull.snapTo((pull.value + undone * Resistance).coerceAtLeast(0f)) }
                return Offset(0f, undone)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // Drag the page had no use for, because it is already at its top.
                if (source != NestedScrollSource.UserInput || available.y <= 0f || isReloading || !isEnabled) return Offset.Zero
                scope.launch { pull.snapTo((pull.value + available.y * Resistance).coerceAtMost(mark * MaxOverPull)) }
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pull.value <= 0f || isReloading) return Velocity.Zero
                if (pull.value >= mark) {
                    reload()
                } else {
                    scope.launch { pull.animateTo(0f, MellowMotion.settle()) }
                }
                // A pull is not a fling: the page should not coast when it is let go.
                return available
            }
        }
    }

    Box(modifier.nestedScroll(connection)) {
        content()

        val showing by remember { derivedStateOf { pull.value > 0.5f } }
        if (showing || reloading) {
            GlassSurface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = topInset)
                    .size(DiscSize)
                    .graphicsLayer {
                        // Out from above the edge, to its resting place as the pull reaches the mark.
                        val progress = (pull.value / mark).coerceIn(0f, 1f)
                        translationY = -size.height + progress * (size.height + rest) + (pull.value - mark).coerceAtLeast(0f) * 0.4f
                        alpha = progress
                        val grown = 0.6f + 0.4f * progress
                        scaleX = grown
                        scaleY = grown
                    }
                    .semantics { if (reloading) contentDescription = "Looking for music" },
                shape = GlassShape.Capsule,
            ) {
                Icon(
                    icon = MellowIcons.Reload,
                    contentDescription = null,
                    size = 20.dp,
                    modifier = Modifier.graphicsLayer {
                        rotationZ = if (isReloading) {
                            engine.tick // Turn with every frame of light.
                            (engine.seconds / TurnSeconds * 360f) % 360f
                        } else {
                            // Wound round by the pull itself, so it answers the finger.
                            pull.value / mark * 300f
                        }
                    },
                )
            }
        }
    }
}

/** How far the page has to be pulled, after resistance, for letting go to reload it. */
private val PullToTrigger = 76.dp
private val RestBelowInset = 14.dp
private val DiscSize = 44.dp

/** A pull moves the disc by half of what the finger moves: it should feel like stretching something. */
private const val Resistance = 0.5f
private const val MaxOverPull = 1.5f

/** How long the arrows take to go round once while reloading. */
private const val TurnSeconds = 0.9f
