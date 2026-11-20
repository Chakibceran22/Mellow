package com.mellow.core.designsystem.theme

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/**
 * Motion is springs only: every animated value can be interrupted mid-flight and carries
 * its velocity into the next target, which is what makes surfaces feel physical.
 */
object MellowMotion {
    /** A control answering a finger. Quick, with a visible bounce. */
    fun <T> press(): SpringSpec<T> = spring(dampingRatio = 0.52f, stiffness = 600f)

    /** Something sliding to a new resting place, like the tab selection. */
    fun <T> glide(): SpringSpec<T> = spring(dampingRatio = 0.74f, stiffness = 420f)

    /** Large surfaces such as the player sheet. Settles without overshoot. */
    fun <T> settle(): SpringSpec<T> = spring(dampingRatio = 0.90f, stiffness = 420f)

    /** Slow changes of state, like artwork resting when playback pauses. */
    fun <T> ease(): SpringSpec<T> = spring(dampingRatio = 0.70f, stiffness = 170f)
}
