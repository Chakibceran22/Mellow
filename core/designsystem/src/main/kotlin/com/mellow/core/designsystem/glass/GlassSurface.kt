package com.mellow.core.designsystem.glass

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mellow.core.designsystem.component.LocalContentColor
import com.mellow.core.designsystem.light.LocalLighting
import com.mellow.core.designsystem.theme.MellowTheme
import com.mellow.core.designsystem.theme.asShadowCaster

/**
 * A piece of glass.
 *
 * It shows the enclosing [GlassScene]'s frost (the scene behind it, blurred once and shared
 * by every surface), bent at the rim and lit by the app's [com.mellow.core.designsystem.light.LightingEngine]. Because it
 * redraws with the engine, the rim light follows the phone's tilt and the music on its own.
 *
 * With [onClick] set the surface is a control: it swells slightly under the finger and a
 * small light blooms where it was touched.
 *
 * Glass is for the layer that floats above content (bars, controls). Keep content itself
 * on plain surfaces.
 *
 * Where there is nothing behind it to bend (over the lit room, or on top of another piece
 * of glass, such as a button on a sheet) it is painted directly instead: the same tint,
 * rim and light, without the copy of the backdrop that bending needs. That happens by
 * itself; nothing has to ask for it.
 *
 * @param pressScale size at full press, relative to rest. Small controls can take more
 *   than large surfaces.
 * @param contentColor what text and icons on the glass take. Tinted glass sets its own.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: GlassShape = GlassShape.Capsule,
    style: GlassStyle = MellowTheme.glass.regular,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    role: Role? = null,
    pressScale: Float = 1.03f,
    contentAlignment: Alignment = Alignment.Center,
    contentColor: Color = MellowTheme.colors.ink,
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = LocalBackdrop.current
    val engine = LocalLighting.current
    val active = LocalGlassActive.current
    // Nothing behind it worth bending: paint it directly. Older phones have no shader to
    // do that with and keep their own fallbacks.
    val plain = (LocalOnGlass.current || backdrop?.refracts == false) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val lightColor = MellowTheme.lighting.specular

    val layer = rememberGraphicsLayer()
    val painter = remember { GlassPainter() }
    val frame = remember { GlassFrame() }
    val placement = remember { Placement() }
    val interactionSource = remember { MutableInteractionSource() }
    val press = rememberPressState(interactionSource)
    val shadowCaster = remember(shape) { shape.asShape().asShadowCaster() }

    // Its standing request for a strip of the scene to be frosted where it sits.
    val claim = remember(backdrop, plain) { if (plain) null else backdrop?.claim() }
    DisposableEffect(backdrop, claim) {
        onDispose { if (backdrop != null && claim != null) backdrop.release(claim) }
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                val scale = 1f + (pressScale - 1f) * press.amount
                scaleX = scale
                scaleY = scale
            }
            .then(
                // Painted glass draws its own shadow; see GlassPainter.
                if (plain) {
                    Modifier
                } else {
                    Modifier.shadow(
                        elevation = style.elevation,
                        shape = shadowCaster,
                        clip = false,
                        ambientColor = style.shadow,
                        spotColor = style.shadow,
                    )
                },
            )
            .onPlaced { placement.coordinates = it }
            .drawWithContent {
                // Redraw with every frame of light, unless the scene is covered.
                if (active) engine.tick

                // Find this surface inside the backdrop. Measured from the centre so that
                // the press scale magnifies what is underneath instead of sliding it.
                val source = backdrop?.coordinates
                val self = placement.coordinates
                frame.origin = if (source != null && self != null && source.isAttached && self.isAttached) {
                    source.localPositionOf(self, size.center) - size.center
                } else {
                    Offset.Zero
                }
                // Ask for the frost under it. Besides its own bounds it reads a little
                // beyond them (the bend at the rim) and may be about to move.
                frame.plain = plain
                frame.band = if (backdrop != null && claim != null) {
                    backdrop.bandFor(claim, Rect(frame.origin, size), style.refraction.toPx() + MotionSlack.toPx())
                } else {
                    null
                }
                frame.shape = shape
                frame.style = style
                frame.lightColor = lightColor
                frame.pressAmount = press.amount
                frame.pressPosition = press.position

                with(painter) { drawGlass(layer, backdrop, engine, frame) }
                drawContent()
            }
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClickLabel = onClickLabel,
                        role = role,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = contentAlignment,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor, LocalOnGlass provides true) {
            content()
        }
    }
}

private class Placement {
    var coordinates: LayoutCoordinates? = null
}

/** Room around a surface that is frosted with it, so a small move does not uncover it. */
private val MotionSlack = 20.dp
