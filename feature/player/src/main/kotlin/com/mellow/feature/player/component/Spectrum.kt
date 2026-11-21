package com.mellow.feature.player.component

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.mellow.core.designsystem.light.LocalLighting
import com.mellow.core.designsystem.theme.MellowTheme
import com.mellow.core.playback.SpectrumBands
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * The music, drawn: a string of beads across the player that the sound pulls out into
 * bars. The lowest notes are in the middle and the pitch rises towards both ends, so a
 * bass drum swells the centre and cymbals flicker at the edges. With nothing playing it
 * settles back into beads and holds still.
 *
 * It moves on the lighting engine's clock, like everything in the app that moves by
 * itself. That clock steps sixty times a second, so this is drawn sixty times a second at
 * most, however fast the screen is, and not at all once the beads have come to rest. It
 * has a layer to itself: redrawing it redraws nothing else on the screen.
 *
 * It is a picture of the sound and says nothing a screen reader could use, so it is
 * hidden from one.
 *
 * @param read fills its argument with the level of each of [SpectrumBands] bands, 0 to 1,
 *   lowest notes first, and returns false when nothing is sounding. Called while drawing,
 *   so it has to be quick and must not allocate.
 * @param playing whether to listen at all. False while paused, and while off screen.
 */
@Composable
internal fun Spectrum(
    read: (FloatArray) -> Boolean,
    playing: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MellowTheme.colors.accent,
) {
    val engine = LocalLighting.current
    val beads = remember { Beads() }

    Canvas(modifier.graphicsLayer().clearAndSetSemantics { }) {
        // How long since the last drawing, on the engine's clock (which starts over now and then).
        val now = engine.seconds
        var elapsed = now - beads.drawnAt
        if (elapsed < 0f || elapsed > LongestStep) elapsed = LongestStep
        beads.drawnAt = now

        // Each bead goes after the level it is given: quickly on the way up, so a beat
        // lands when it is heard, and slowly on the way down, so it can be seen.
        val heard = playing && read(beads.heard)
        val rise = 1f - exp(-elapsed / RiseSeconds)
        val fall = 1f - exp(-elapsed / FallSeconds)
        var moving = false
        for (band in 0 until SpectrumBands) {
            val goal = if (heard) beads.heard[band] else 0f
            val was = beads.shown[band]
            val next = was + (goal - was) * if (goal > was) rise else fall
            beads.shown[band] = next
            if (abs(goal - next) > Still) moving = true
        }

        // Mirrored about the middle: band 0 once in the centre, the rest on both sides.
        val count = SpectrumBands * 2 - 1
        val pitch = size.width / count
        val bead = min(pitch * BeadShare, MaxBead.toPx())
        val middle = size.height / 2f
        val reach = middle - bead / 2f
        for (index in 0 until count) {
            val level = beads.shown[abs(index - (SpectrumBands - 1))]
            val half = bead / 2f + level * reach
            drawRoundRect(
                color = color,
                topLeft = Offset((index + 0.5f) * pitch - bead / 2f, middle - half),
                size = Size(bead, half * 2f),
                cornerRadius = CornerRadius(bead / 2f),
                // A bead at rest sits back; a bar at full stretch is solid.
                alpha = RestingAlpha + (1f - RestingAlpha) * level,
            )
        }

        // Draw again at the next step of the clock, for as long as there is something to show.
        if (playing || moving) engine.tick
    }
}

/** Where each bead is and where it is heading. Kept between drawings; never allocated in one. */
private class Beads {
    val heard = FloatArray(SpectrumBands)
    val shown = FloatArray(SpectrumBands)
    var drawnAt = 0f
}

private const val RiseSeconds = 0.035f
private const val FallSeconds = 0.16f

/** A gap longer than this between drawings is treated as one step, so nothing lurches after a pause. */
private const val LongestStep = 0.05f

/** Closer than this to where it is going, a bead counts as arrived. */
private const val Still = 0.004f

/** How much of its slot a bead fills; the rest is the gap to its neighbour. */
private const val BeadShare = 0.44f
private val MaxBead = 4.dp
private const val RestingAlpha = 0.42f
