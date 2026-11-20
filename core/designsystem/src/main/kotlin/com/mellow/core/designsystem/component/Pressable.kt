package com.mellow.core.designsystem.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import com.mellow.core.designsystem.glass.rememberPressState

/**
 * Makes an opaque element tappable the Mellow way: it sinks a little under the finger and
 * springs back, with no ripple. Glass does the opposite (it swells) and handles its own
 * press, so use this only on plain content such as rows and cards.
 *
 * @param pressedScale size at full press. Larger elements should move less.
 * @param onLongClick what pressing and holding does, if anything. The phone ticks when it fires.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.pressable(
    onClick: () -> Unit,
    onClickLabel: String? = null,
    role: Role? = Role.Button,
    enabled: Boolean = true,
    pressedScale: Float = 0.96f,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val press = rememberPressState(interactionSource)
    return this
        .graphicsLayer {
            val amount = press.amount
            val scale = 1f + (pressedScale - 1f) * amount
            scaleX = scale
            scaleY = scale
            alpha = 1f - 0.12f * amount.coerceIn(0f, 1f)
        }
        .combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onLongClickLabel = onLongClickLabel,
            onLongClick = onLongClick,
            onClick = onClick,
        )
}
