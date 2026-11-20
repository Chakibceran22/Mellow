package com.mellow.core.designsystem.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import com.mellow.core.designsystem.theme.MellowMotion
import com.mellow.core.designsystem.theme.MellowTheme

/**
 * A progress bar you can grab. At rest it is a thin line; under the finger it thickens,
 * so the control is quiet until it is in use.
 *
 * @param fraction progress to show, 0..1. While dragging, the caller should feed back the
 *   value from [onScrub] so the bar follows the finger.
 * @param onScrub called continuously with the position under the finger.
 * @param onScrubFinished called once when the finger lifts, with the final position.
 */
@Composable
fun Scrubber(
    fraction: Float,
    onScrub: (Float) -> Unit,
    onScrubFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = "Playback position",
) {
    val colors = MellowTheme.colors
    var dragging by remember { mutableStateOf(false) }
    val thickness by animateDpAsState(if (dragging) 14.dp else 6.dp, MellowMotion.press(), label = "scrubber")
    val currentOnScrub by rememberUpdatedState(onScrub)
    val currentOnFinished by rememberUpdatedState(onScrubFinished)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .semantics {
                this.contentDescription = contentDescription
                progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
                setProgress { target ->
                    currentOnFinished(target.coerceIn(0f, 1f))
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    var last = (down.position.x / size.width).coerceIn(0f, 1f)
                    dragging = true
                    currentOnScrub(last)
                    drag(down.id) { change ->
                        change.consume()
                        last = (change.position.x / size.width).coerceIn(0f, 1f)
                        currentOnScrub(last)
                    }
                    dragging = false
                    currentOnFinished(last)
                }
            },
    ) {
        val height = thickness.toPx()
        val top = (size.height - height) / 2f
        val radius = CornerRadius(height / 2f)
        drawRoundRect(colors.ink.copy(alpha = 0.14f), Offset(0f, top), Size(size.width, height), radius)
        val filled = size.width * fraction.coerceIn(0f, 1f)
        if (filled > 0f) {
            // Never narrower than it is tall, so the filled end stays round.
            drawRoundRect(colors.ink, Offset(0f, top), Size(filled.coerceAtLeast(height), height), radius)
        }
    }
}
