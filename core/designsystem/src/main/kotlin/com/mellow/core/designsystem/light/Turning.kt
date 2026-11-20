package com.mellow.core.designsystem.light

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Turns something round steadily while [running], and holds its angle when not.
 *
 * It is timed by the lighting engine rather than by an animation of its own. Everything
 * in the app that moves by itself shares that one clock, which ticks sixty times a second;
 * a private animation would run at the screen's full rate and drag the whole screen up to
 * it, twice the drawing for a difference nobody can see in something this slow.
 *
 * With reduced motion on, the engine holds still and so does this.
 */
@Composable
fun Modifier.turning(running: Boolean, secondsPerTurn: Float): Modifier {
    val engine = LocalLighting.current
    val turn = remember { Turn() }
    return graphicsLayer {
        if (running) {
            engine.tick // Move with every frame of light.
            turn.advanceTo(engine.seconds, degreesPerSecond = 360f / secondsPerTurn)
        } else {
            turn.rest()
        }
        rotationZ = turn.angle
    }
}

/** An angle that grows with the engine's clock while it is being advanced. */
private class Turn {
    var angle = 0f
        private set

    private var last = Float.NaN

    fun advanceTo(seconds: Float, degreesPerSecond: Float) {
        if (!last.isNaN()) {
            // The engine's clock starts over once an hour.
            val elapsed = (seconds - last).let { if (it < 0f) it + LightingEngine.ClockSeconds else it }
            angle = (angle + elapsed * degreesPerSecond) % 360f
        }
        last = seconds
    }

    fun rest() {
        last = Float.NaN
    }
}
