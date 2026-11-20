package com.mellow.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.mellow.core.designsystem.glass.GlassShape
import com.mellow.core.designsystem.glass.GlassSurface
import com.mellow.core.designsystem.theme.MellowMotion
import com.mellow.core.designsystem.theme.MellowTheme
import kotlinx.coroutines.launch

/**
 * A modal: a slab of thick glass that rises from the bottom of the screen and floats just
 * clear of its edges, over a dimmed page. For a choice that has to be made before going on.
 *
 * It leaves by a tap on the dimmed page, by being dragged or flicked down, or when the
 * caller sets [visible] false; all three call [onDismiss]. The system back button is the
 * caller's to wire, with a `BackHandler`.
 *
 * Being glass it follows the rule for glass: call it from the content layer of a
 * `GlassScene` that fills the screen. It rides above the keyboard when one is up.
 *
 * It is as tall as what is in it, up to the room there is between the status bar and the
 * bottom of the screen (or the keyboard). Past that, what is in it scrolls.
 *
 * It stays composed while it slides away, so keep giving it the same [content] until it
 * has gone.
 */
@Composable
fun BoxScope.GlassSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MellowTheme.colors
    val scope = rememberCoroutineScope()

    // 0 = away below the screen, 1 = at rest. Read in draw blocks only.
    val rise = remember { Animatable(0f) }
    // How far the finger has pulled it down from rest, in pixels.
    val pull = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        if (visible) pull.snapTo(0f)
        rise.animateTo(if (visible) 1f else 0f, MellowMotion.settle())
    }
    val onScreen by remember { derivedStateOf { rise.value > 0f } }
    if (!visible && !onScreen) return

    val dismissPull = with(LocalDensity.current) { DismissPull.toPx() }

    Box(
        Modifier
            .matchParentSize()
            .drawBehind { drawRect(colors.scrim, alpha = rise.value.coerceIn(0f, 1f)) }
            .clickable(interactionSource = null, indication = null, onClickLabel = "Close", onClick = onDismiss),
    )

    GlassSurface(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .statusBarsPadding()
            .imePadding()
            .navigationBarsPadding()
            .padding(start = SheetMargin, end = SheetMargin, top = SheetMargin, bottom = SheetMargin)
            .fillMaxWidth()
            .graphicsLayer {
                // Parked a little more than its own height below where it rests.
                translationY = (1f - rise.value) * (size.height + 120.dp.toPx()) + pull.value
            }
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta ->
                    scope.launch { pull.snapTo((pull.value + delta).coerceAtLeast(0f)) }
                },
                onDragStopped = { velocity ->
                    if (pull.value > dismissPull || velocity > FlickVelocity) {
                        onDismiss()
                    } else {
                        pull.animateTo(0f, MellowMotion.settle())
                    }
                },
            )
            // A tap on the sheet itself is not a tap on the page behind it.
            .pointerInput(Unit) { detectTapGestures { } },
        shape = GlassShape.Continuous(MellowTheme.radii.sheet),
        style = MellowTheme.glass.thick,
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 20.dp)) {
            // The grabber: says the sheet can be pulled.
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 36.dp, height = 5.dp)
                    .drawBehind { drawOutline(CircleShape.createOutline(size, layoutDirection, this), colors.inkFaint) },
            )
            // Scrolling is switched on only when there is something to scroll. Left on,
            // it would swallow the drag that pulls the sheet down.
            val scroll = rememberScrollState()
            Box(Modifier.padding(top = 14.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(scroll, enabled = scroll.maxValue > 0),
                    content = content,
                )
            }
        }
    }
}

private val SheetMargin = 10.dp
private val DismissPull = 96.dp
private const val FlickVelocity = 1400f
