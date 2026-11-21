package com.mellow.core.designsystem.glass

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.semantics.Role
import com.mellow.core.designsystem.component.LocalContentColor
import com.mellow.core.designsystem.light.LocalLighting
import com.mellow.core.designsystem.theme.MellowTheme
import com.mellow.core.designsystem.theme.asShadowCaster

/**
 * A piece of glass.
 *
 * It shows what the enclosing [GlassScene] has behind it, frosted, bent at the rim and lit
 * by the app's [com.mellow.core.designsystem.light.LightingEngine]. Because it redraws with the engine, the rim light
 * follows the phone's tilt and the music on its own.
 *
 * The frost is its own: the strip of the scene it lies over, taken afresh each time it
 * draws, from where it is at that moment. So it can be slid, dragged, resized or carried
 * along by a page and is never caught without something behind it. Between two steps of
 * the light, a surface that is being moved is not drawn again: the pane it has already
 * drawn is moved as it is, which costs nothing and keeps a slide at the screen's full rate.
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

    // Glass that shows the backdrop through itself needs room to keep its piece of it.
    // Painted glass does not, and neither do phones too old to blur.
    val optics = if (!plain && backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) rememberOptics() else null
    val painter = remember { GlassPainter() }
    val frame = remember { GlassFrame() }
    val placement = remember { Placement() }
    val interactionSource = remember { MutableInteractionSource() }
    val press = rememberPressState(interactionSource)
    val shadowCaster = remember(shape) { shape.asShape().asShadowCaster() }

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
                // Frost the strip of the scene it is lying over, now that it is known
                // where that is.
                frame.plain = plain
                frame.frost = if (backdrop != null && optics != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val bounds = Rect(frame.origin, size)
                    val frosted = with(optics.frost) { recordUnder(backdrop.scene, bounds, GlassEdge.toFloat(), backdrop.frost.toPx()) }
                    if (frosted) optics.frost else null
                } else {
                    null
                }
                frame.shape = shape
                frame.style = style
                frame.lightColor = lightColor
                frame.pressAmount = press.amount
                frame.pressPosition = press.position

                with(painter) { drawGlass(optics?.pane, backdrop, engine, frame) }
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

/** What a surface needs to show the backdrop through itself: its own frosted strip, and the pane that strip is bent in. */
private class Optics(val frost: Frost, val pane: GraphicsLayer)

@Composable
private fun rememberOptics(): Optics {
    val source = rememberGraphicsLayer()
    val kept = rememberGraphicsLayer()
    val pane = rememberGraphicsLayer()
    return remember(source, kept, pane) { Optics(Frost(source, kept), pane) }
}
