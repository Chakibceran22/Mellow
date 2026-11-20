package com.mellow.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mellow.core.designsystem.light.LocalLighting
import com.mellow.core.designsystem.theme.MellowTheme
import kotlin.math.PI
import kotlin.math.sin

/**
 * Paints a placeholder in the shape of what is about to appear.
 *
 * Skeletons do not shimmer on their own clocks. They are driven by the lighting engine,
 * and each block's brightness depends on where it sits on screen, so one band of light is
 * seen travelling across the whole page from the top-left, the same direction the room is
 * lit from. Each block is a single flat colour at any instant.
 *
 * @param shape the outline of the real content, so the placeholder and the thing it
 *   stands for have the same silhouette.
 * @param visible false once the real content is there; the modifier then does nothing.
 */
@Composable
fun Modifier.skeleton(shape: Shape = SkeletonLine, visible: Boolean = true): Modifier {
    if (!visible) return this

    val engine = LocalLighting.current
    val color = MellowTheme.colors.skeleton
    val place = remember { Place() }

    return this
        .onPlaced { place.inRoot = it.positionInRoot() }
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val wavelength = Wavelength.toPx()
            onDrawBehind {
                engine.tick // Redraw with the light.
                val travelled = (place.inRoot.x + place.inRoot.y) / wavelength
                val wave = 0.5f + 0.5f * sin(TwoPi * (engine.seconds / PeriodSeconds - travelled))
                drawOutline(outline, color.copy(alpha = color.alpha * (Dimmest + (1f - Dimmest) * wave)))
            }
        }
}

/** A placeholder block. Size it with [modifier] to match what it stands in for. */
@Composable
fun Skeleton(modifier: Modifier = Modifier, shape: Shape = SkeletonLine) {
    Spacer(modifier.skeleton(shape))
}

/**
 * A placeholder for one line of text. It takes up the full [lineHeight] of the text style
 * it stands in for, with a bar [ink] tall where the letters will sit, so the real line
 * drops in without anything around it moving.
 */
@Composable
fun SkeletonTextLine(width: Dp, lineHeight: Dp, ink: Dp, modifier: Modifier = Modifier) {
    Box(modifier.height(lineHeight), contentAlignment = Alignment.CenterStart) {
        Skeleton(Modifier.size(width, ink))
    }
}

/** The shape of a placeholder for a line of text. */
val SkeletonLine: Shape = RoundedCornerShape(7.dp)

private class Place {
    var inRoot: Offset = Offset.Zero
}

private const val TwoPi = (2.0 * PI).toFloat()
private const val PeriodSeconds = 1.6f
private const val Dimmest = 0.40f
private val Wavelength = 1100.dp
