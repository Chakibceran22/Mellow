package com.mellow.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mellow.core.designsystem.glass.GlassShape
import com.mellow.core.designsystem.glass.GlassSurface
import com.mellow.core.designsystem.theme.MellowMotion
import com.mellow.core.designsystem.theme.MellowTheme

/**
 * A line of glass that says what just happened ("Added 3 songs to Evening") and goes away
 * by itself. For confirming something the user did, never for something they must answer.
 *
 * The caller owns the timing: show it by passing a message, hide it by passing null.
 * Glass, so it belongs in the content layer of a `GlassScene`.
 */
@Composable
fun GlassToast(message: String?, modifier: Modifier = Modifier) {
    // What it last said, kept so the words do not vanish while it is fading out.
    val last = remember { LastMessage() }
    if (message != null) last.text = message

    AnimatedVisibility(
        visible = message != null,
        modifier = modifier,
        enter = fadeIn() + scaleIn(MellowMotion.press(), initialScale = 0.86f) + slideInVertically(MellowMotion.glide()) { it / 2 },
        exit = fadeOut() + scaleOut(MellowMotion.glide(), targetScale = 0.92f),
    ) {
        GlassSurface(shape = GlassShape.Capsule, style = MellowTheme.glass.thick) {
            Text(
                text = last.text,
                style = MellowTheme.type.callout,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier
                    .padding(horizontal = 22.dp, vertical = 13.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

private class LastMessage {
    var text: String = ""
}
