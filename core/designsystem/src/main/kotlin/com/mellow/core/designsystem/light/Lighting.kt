package com.mellow.core.designsystem.light

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

/** The app's single [LightingEngine]. Provided by `MellowTheme`. */
val LocalLighting = staticCompositionLocalOf<LightingEngine> {
    error("No LightingEngine. Wrap the content in MellowTheme.")
}

/**
 * The light is simulated at most this often. Lamps move slowly, so 60 steps a second is
 * indistinguishable from the panel's full rate and halves the work on a 120 Hz screen.
 */
private const val FrameIntervalNanos = 15_000_000L

/**
 * Creates the engine and runs it for as long as the app is in the foreground. Leaving the
 * resumed state stops both the frame loop and the tilt sensor, so a backgrounded app that
 * is only playing audio does no rendering work at all.
 */
@Composable
internal fun rememberLightingEngine(): LightingEngine {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val engine = remember { LightingEngine() }
    val tilt = remember { TiltSensor(context) }

    LifecycleResumeEffect(tilt) {
        tilt.start()
        onPauseOrDispose { tilt.stop() }
    }

    LaunchedEffect(engine, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Re-read on every resume: the user may have changed the setting meanwhile.
            val animate = !context.prefersReducedMotion()
            var last = 0L
            while (true) {
                withFrameNanos { now ->
                    if (now - last >= FrameIntervalNanos) {
                        last = now
                        engine.advance(now, tilt.x, tilt.y, animate)
                    }
                }
            }
        }
    }
    return engine
}

/** True when the user has turned animations off in system settings. */
internal fun Context.prefersReducedMotion(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
