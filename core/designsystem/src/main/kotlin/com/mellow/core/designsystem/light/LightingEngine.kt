package com.mellow.core.designsystem.light

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One light simulation for the whole app.
 *
 * It owns a key light (a direction) and three lamps (positions above the surface) and
 * moves them from three inputs:
 *
 *  - time: the lamps drift on slow, closed orbits;
 *  - the phone's tilt: the key light stays fixed in the room, so it swings across the
 *    screen as the device turns, the way a real reflection would;
 *  - the music: [energySource] reports how loud playback is, and the lamps breathe with it.
 *
 * Both the background ([LightField]) and every piece of glass read the same state, which
 * is why a highlight on a button always agrees with the light on the wall behind it.
 *
 * Renderers subscribe by reading [tick] inside a draw block; nothing here recomposes.
 */
@Stable
class LightingEngine internal constructor() {

    /** Bumped once per simulated frame. */
    var tick by mutableIntStateOf(0)
        private set

    /** Loudness of playback, 0..1. Called once per frame from the main thread. */
    var energySource: () -> Float = { 0f }

    /** Where the surface's folds are in their loop, 0..2π. */
    internal var phase = 0f
        private set

    /** Seconds the light has been running. Frozen when the user has turned animations off. */
    /** Seconds on the engine's clock, which starts over every [ClockSeconds]. Read it where [tick] is read. */
    var seconds = 0f
        internal set

    /** Unit vector from the surface toward the key light. x right, y down, z toward the viewer. */
    internal var keyX = RestKeyX
        private set
    internal var keyY = RestKeyY
        private set
    internal var keyZ = RestKeyZ
        private set

    /** Three lamps, four floats each: x, y in the unit square, height, intensity. */
    internal val lamps = FloatArray(LampCount * 4)

    /** Smoothed loudness, 0..1. */
    internal var energy = 0f
        private set

    /** The playing track's colour, eased toward its target, and how much of it applies. */
    internal var accentRed = 0f
        private set
    internal var accentGreen = 0f
        private set
    internal var accentBlue = 0f
        private set
    internal var accentAmount = 0f
        private set

    private var accentTarget: Color? = null
    private var startNanos = -1L

    init {
        place(phase = 0f, tiltX = 0f)
    }

    /** Tints the first lamp. Pass null to return it to the theme's own colour. */
    fun setAccent(color: Color?) {
        if (accentAmount == 0f && color != null) {
            // Nothing to ease from yet: start on the colour rather than fading up from black.
            accentRed = color.red
            accentGreen = color.green
            accentBlue = color.blue
        }
        accentTarget = color
    }

    internal fun advance(frameNanos: Long, tiltX: Float, tiltY: Float, animate: Boolean) {
        if (startNanos < 0) startNanos = frameNanos

        if (animate) {
            val seconds = (frameNanos - startNanos) / 1e9
            phase = ((seconds % LoopSeconds) / LoopSeconds * TwoPi).toFloat()
            this.seconds = (seconds % ClockSeconds).toFloat()
        }

        // Loudness rises quickly and lets go slowly, like a meter needle.
        val level = if (animate) energySource().coerceIn(0f, 1f) else 0f
        val loud = (level * EnergyGain).coerceAtMost(1f)
        energy += (loud - energy) * if (loud > energy) EnergyAttack else EnergyRelease

        accentTarget?.let { target ->
            accentRed += (target.red - accentRed) * AccentEase
            accentGreen += (target.green - accentGreen) * AccentEase
            accentBlue += (target.blue - accentBlue) * AccentEase
        }
        val amount = if (accentTarget != null) 1f else 0f
        accentAmount += (amount - accentAmount) * AccentEase

        // The key light hangs still in the room, above and to the left of someone holding
        // the phone normally. Rolling the phone swings it sideways across the screen;
        // pitching it moves it up and down.
        val x = RestKeyX + RollReach * tiltX + 0.05f * sin(phase * 2f)
        val y = RestKeyY - PitchReach * (tiltY - TiltSensor.RestingY)
        val z = RestKeyZ
        val length = sqrt(x * x + y * y + z * z)
        keyX = x / length
        keyY = y / length
        keyZ = z / length

        place(phase, tiltX)
        tick++
    }

    private fun place(phase: Float, tiltX: Float) {
        val breath = 1f + 0.06f * sin(phase * 5f)
        val pulse = breath * (1f + EnergyLift * energy)
        val sway = tiltX * 0.10f

        // Closed orbits: every multiplier of `phase` is a whole number, so the loop has no seam.
        set(0, 0.30f + 0.26f * sin(phase * 3f + 0.4f) + sway, 0.20f + 0.14f * cos(phase * 2f), 0.44f, pulse)
        set(1, 0.74f + 0.22f * cos(phase * 2f + 1.9f) + sway, 0.56f + 0.18f * sin(phase * 3f + 0.7f), 0.52f, 0.85f * pulse)
        set(2, 0.34f + 0.30f * sin(phase + 3.1f) + sway, 0.90f + 0.10f * cos(phase * 4f + 1.1f), 0.40f, 0.75f * pulse)
    }

    private fun set(index: Int, x: Float, y: Float, height: Float, intensity: Float) {
        val i = index * 4
        lamps[i] = x
        lamps[i + 1] = y
        lamps[i + 2] = height
        lamps[i + 3] = intensity
    }

    internal companion object {
        const val LampCount = 3

        /** How long [seconds] runs before starting over. Kept small so it stays exact as a float. */
        const val ClockSeconds = 3600f

        /** One full cycle of the room. Long enough that the motion never reads as a loop. */
        private const val LoopSeconds = 240.0
        private const val TwoPi = 2.0 * PI

        // Resting key light: above and to the left of the viewer.
        private const val RestKeyX = -0.46f
        private const val RestKeyY = -0.52f
        private const val RestKeyZ = 0.70f
        private const val RollReach = 0.90f
        private const val PitchReach = 0.50f

        private const val EnergyGain = 3.2f
        private const val EnergyAttack = 0.30f
        private const val EnergyRelease = 0.06f
        private const val EnergyLift = 0.34f
        private const val AccentEase = 0.05f
    }
}
