package com.mellow.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mellow.core.designsystem.light.LocalLighting
import com.mellow.core.designsystem.theme.MellowTheme

/**
 * Three bars that dance while [playing] and rest low when paused. Marks the row of the
 * track that is on, where a duration would otherwise sit.
 *
 * Like everything that moves by itself, they are timed by the lighting engine: one clock
 * for the whole app, ticking sixty times a second, instead of an animation of their own
 * that would redraw the screen at its full rate for as long as music played.
 */
@Composable
fun EqualizerBars(
    playing: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MellowTheme.colors.accent,
    size: Dp = 18.dp,
) {
    val engine = LocalLighting.current

    Canvas(modifier.size(size)) {
        val gap = this.size.width * 0.16f
        val barWidth = (this.size.width - gap * 2) / 3f
        if (playing) engine.tick // Redraw with every frame of light.
        for (index in 0 until BarCount) {
            val level = if (playing) Lowest[index] + (Highest[index] - Lowest[index]) * bounce(engine.seconds, Periods[index]) else RestingLevel
            val height = this.size.height * level
            drawRoundRect(
                color = color,
                topLeft = Offset(index * (barWidth + gap), this.size.height - height),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

/** Up and back down in a straight line once per [period] seconds: 0 at the bottom, 1 at the top. */
private fun bounce(seconds: Float, period: Float): Float {
    val through = (seconds % period) / period
    return if (through < 0.5f) through * 2f else (1f - through) * 2f
}

private const val BarCount = 3
private const val RestingLevel = 0.28f

// Three periods with no common beat, so the pattern never visibly repeats.
private val Periods = floatArrayOf(0.86f, 1.22f, 1.04f)
private val Lowest = floatArrayOf(0.25f, 0.35f, 0.45f)
private val Highest = floatArrayOf(1f, 1f, 0.9f)
