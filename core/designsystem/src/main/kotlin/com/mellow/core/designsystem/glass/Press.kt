package com.mellow.core.designsystem.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.mellow.core.designsystem.theme.MellowMotion
import kotlinx.coroutines.launch

/**
 * How pressed something is, as a spring. [amount] runs 0 (at rest) to 1 (held), with
 * overshoot on the way back; [position] is where the finger landed.
 */
@Stable
class PressState internal constructor() {
    internal val spring = Animatable(0f)

    val amount: Float get() = spring.value

    var position: Offset by mutableStateOf(Offset.Unspecified)
        internal set
}

/**
 * Follows [interactionSource] with a spring. A quick tap is released before the spring has
 * moved, so a release always lets the press reach full depth first; otherwise taps would
 * give no feedback at all.
 */
@Composable
fun rememberPressState(interactionSource: InteractionSource): PressState {
    val state = remember { PressState() }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    state.position = interaction.pressPosition
                    launch { state.spring.animateTo(1f, MellowMotion.press()) }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> launch {
                    if (state.spring.value < 0.6f) {
                        state.spring.animateTo(1f, spring(dampingRatio = 1f, stiffness = 2400f))
                    }
                    state.spring.animateTo(0f, MellowMotion.press())
                }
            }
        }
    }
    return state
}
