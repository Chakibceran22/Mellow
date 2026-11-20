package com.mellow.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.mellow.core.designsystem.icon.MellowIcons
import com.mellow.core.designsystem.theme.MellowMotion
import com.mellow.core.designsystem.theme.MellowTheme

/**
 * Which items the user has picked out, by key.
 *
 * Selecting is a mode: it starts when the first item is picked (by pressing and holding
 * it) and ends when the last is let go or [clear] is called. While it is on, a tap picks
 * or drops an item instead of opening it. One of these is shared by every list in the app,
 * so songs picked on one page are still picked on the next.
 *
 * Keys are kept in the order they were picked.
 */
@Stable
class SelectionState {

    var keys: Set<String> by mutableStateOf(emptySet())
        private set

    /** True while at least one item is picked. */
    val active: Boolean get() = keys.isNotEmpty()

    val count: Int get() = keys.size

    operator fun contains(key: String): Boolean = key in keys

    /** Picks [key], or drops it if it is already picked. */
    fun toggle(key: String) {
        keys = if (key in keys) keys - key else keys + key
    }

    fun clear() {
        keys = emptySet()
    }
}

/**
 * For a row in a list: whether [key] is picked, or null while nothing is being selected.
 *
 * Reading the selection this way, a row is redrawn only when its own answer changes.
 * Reading [SelectionState.keys] directly would redraw every row on screen each time any
 * one of them was picked.
 */
@Composable
fun SelectionState.pickedOrNull(key: String): Boolean? =
    remember(this, key) { derivedStateOf { if (active) key in keys else null } }.value

/**
 * The mark at the end of a row while selecting: an empty ring, or a filled disc with a
 * tick once the row is picked. It springs as it fills, so a tap is answered at the
 * fingertip even on a long list.
 */
@Composable
fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    val colors = MellowTheme.colors
    val fill by animateFloatAsState(if (selected) 1f else 0f, MellowMotion.press(), label = "selectionMark")

    Box(
        modifier = modifier
            .size(MarkSize)
            .drawBehind {
                drawCircle(colors.inkFaint, radius = size.minDimension / 2f - RingWidth.toPx() / 2f, style = Stroke(RingWidth.toPx()))
                val grown = fill.coerceIn(0f, 1.2f)
                if (grown > 0f) drawCircle(colors.accent, radius = size.minDimension / 2f * grown)
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon = MellowIcons.Selected,
            contentDescription = null,
            tint = colors.onAccent,
            size = 14.dp,
            modifier = Modifier.graphicsLayer {
                val shown = fill.coerceIn(0f, 1f)
                alpha = shown
                scaleX = shown
                scaleY = shown
            },
        )
    }
}

private val MarkSize = 24.dp
private val RingWidth = 1.5.dp
