package com.mellow.core.designsystem.glass

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mellow.core.designsystem.theme.MellowTheme
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The frost is kept smaller than the scene, because it is about to be blurred anyway and
 * both the blur and everything that reads it then work on a fraction of the pixels. How
 * much smaller depends on the blur: a light frost still shows the shapes behind it, so it
 * is kept at half size (a quarter of the pixels); a heavy one hides them and can drop to
 * quarter size (a sixteenth).
 */
private fun frostDownscale(blurPx: Float): Float = if (blurPx >= HeavyBlurPx) 4f else 2f

private const val HeavyBlurPx = 30f

/** How far past its own edge a blur reads, as a multiple of its radius. */
private const val BlurReach = 2.5f

/**
 * A strip's sides are whole multiples of this many frost pixels. A surface that is growing
 * or shrinking (the mini player arriving, a sheet changing its question) then keeps the
 * buffers it has for several frames at a time instead of needing new ones every frame.
 */
private const val SizeStep = 16f

/**
 * One surface's own piece of the scene, frosted: the strip the surface lies over, shrunk,
 * blurred, and kept in a buffer for the surface to look through.
 *
 * It belongs to its surface and is made again each time the surface draws, from wherever
 * the surface is at that moment. That is what keeps glass whole while it moves. Frost that
 * is laid out ahead of time, for where a surface was a frame ago, is in the wrong place
 * the moment the surface slides, and a sheet rising from the bottom of the screen then
 * spends the whole trip with nothing behind it.
 */
internal class Frost(
    private val source: GraphicsLayer,
    private val kept: GraphicsLayer,
) {
    /** Where the strip's top-left corner is in the scene, and how big it is there. */
    private var left = 0f
    private var top = 0f
    private var width = 0f
    private var height = 0f

    /** Scene pixels per frost pixel, on each axis. */
    private var scaleX = 1f
    private var scaleY = 1f

    private var blurRadius = -1f
    private var blur: RenderEffect? = null

    /**
     * Frosts what [scene] shows under a surface occupying [bounds] of it.
     *
     * The surface may hang off the scene, or be off it altogether, like a sheet parked
     * below the screen on its way in. It still gets frost: see [drawPatch].
     *
     * @param edge how far outside its own bounds the surface reads, in pixels.
     * @return false only when the scene has not been drawn yet and there is nothing to frost.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    fun DrawScope.recordUnder(scene: GraphicsLayer, bounds: Rect, edge: Float, blurPx: Float): Boolean {
        val sceneWidth = scene.size.width.toFloat()
        val sceneHeight = scene.size.height.toFloat()
        if (sceneWidth <= 0f || sceneHeight <= 0f) return false

        val downscale = frostDownscale(blurPx)
        // A blur reads past the edge of what it shows; so must the strip.
        val margin = edge + blurPx * BlurReach

        // The strip is the surface and its margin, with its corner on a grid of whole
        // frost pixels: that way it keeps one size, and samples the scene the same way,
        // wherever the surface goes. It never hangs off the scene. There is nothing out
        // there, and blurring nothing in would darken the frost along the edge of the
        // screen; the strip is slid back inside instead, where it still covers all of
        // the surface that is over the scene.
        val step = downscale * SizeStep
        // One frost pixel more than is needed, for the corner having been moved onto the grid.
        var width = ceil((bounds.width + margin * 2f + downscale) / step) * step
        var x = floor((bounds.left - margin) / downscale) * downscale
        if (width >= sceneWidth) {
            width = sceneWidth
            x = 0f
        } else {
            x = x.coerceIn(0f, sceneWidth - width)
        }
        var height = ceil((bounds.height + margin * 2f + downscale) / step) * step
        var y = floor((bounds.top - margin) / downscale) * downscale
        if (height >= sceneHeight) {
            height = sceneHeight
            y = 0f
        } else {
            y = y.coerceIn(0f, sceneHeight - height)
        }

        val small = IntSize(ceil(width / downscale).toInt(), ceil(height / downscale).toInt())
        left = x
        top = y
        this@Frost.width = width
        this@Frost.height = height
        scaleX = width / small.width
        scaleY = height / small.height

        // This strip of the scene, shrunk. Everything outside the strip is clipped away
        // by the layer's own bounds, so the rest of the page is not drawn a second time.
        source.record(small) {
            scale(1f / scaleX, 1f / scaleY, pivot = Offset.Zero) {
                translate(-x, -y) { drawLayer(scene) }
            }
        }
        val radius = blurPx / downscale
        if (radius != blurRadius) {
            blurRadius = radius
            blur = if (radius >= 0.5f) BlurEffect(radius, radius, TileMode.Clamp) else null
        }
        source.renderEffect = blur

        // A blur is re-applied every time its layer is drawn. Drawing it into a second,
        // offscreen layer runs it once, at the small size, and keeps the result.
        // With no blur there is nothing to keep, and the extra buffer would be pure cost.
        kept.compositingStrategy = if (blur != null) CompositingStrategy.Offscreen else CompositingStrategy.Auto
        kept.record(small) { drawLayer(source) }
        return true
    }

    /**
     * Draws the patch of frost that lies under a surface whose top-left corner is at
     * [origin] in the scene, scaled back up to full size, leaving [inset] pixels of margin.
     * Call it while recording the surface's pane; it fills the whole pane.
     *
     * Where the surface hangs off the scene there is no frost to draw, so the frost's
     * outermost row or column is pulled out over that part, the way a blur carries the
     * edge of a picture outwards. A surface on its way in is moved between two drawings,
     * and the part of it that comes into view in between has to have something on it
     * that already looks like what is there.
     */
    fun DrawScope.drawPatch(origin: Offset, inset: Float = 0f) {
        // The strip, in the pane's own coordinates.
        val x0 = inset - origin.x + left
        val y0 = inset - origin.y + top
        val y1 = y0 + height
        drawRows(x0, y0)
        if (y0 > 0f) {
            clipRect(0f, 0f, size.width, y0) {
                scale(1f, (y0 + scaleY) / scaleY, pivot = Offset(0f, y0 + scaleY)) { drawRows(x0, y0) }
            }
        }
        if (y1 < size.height) {
            clipRect(0f, y1, size.width, size.height) {
                scale(1f, (size.height - y1 + scaleY) / scaleY, pivot = Offset(0f, y1 - scaleY)) { drawRows(x0, y0) }
            }
        }
    }

    /** The strip, with its first and last columns pulled out sideways over whatever of the pane lies beyond them. */
    private fun DrawScope.drawRows(x0: Float, y0: Float) {
        val x1 = x0 + width
        drawStrip(x0, y0)
        if (x0 > 0f) {
            clipRect(0f, y0, x0, y0 + height) {
                scale((x0 + scaleX) / scaleX, 1f, pivot = Offset(x0 + scaleX, 0f)) { drawStrip(x0, y0) }
            }
        }
        if (x1 < size.width) {
            clipRect(x1, y0, size.width, y0 + height) {
                scale((size.width - x1 + scaleX) / scaleX, 1f, pivot = Offset(x1 - scaleX, 0f)) { drawStrip(x0, y0) }
            }
        }
    }

    private fun DrawScope.drawStrip(x0: Float, y0: Float) {
        scale(scaleX, scaleY, pivot = Offset.Zero) {
            translate(x0 / scaleX, y0 / scaleY) { drawLayer(kept) }
        }
    }
}

/**
 * What a piece of glass looks through: [scene], a recording of everything drawn behind it.
 *
 * Glass does not look at all of it. A tab bar covers a strip along the bottom of the
 * screen, not the screen, so each surface frosts only the strip it lies over (see [Frost]).
 * Frosting the whole screen for the sake of a strip was costing more than drawing the
 * screen itself.
 */
@Stable
class Backdrop internal constructor(internal val scene: GraphicsLayer) {
    internal var coordinates: LayoutCoordinates? = null

    /** False when the scene behind is only soft light: nothing in it would visibly bend, so glass over it is painted directly. */
    internal var refracts = true

    /** How far the scene is diffused before glass sees it. */
    internal var frost: Dp = 0.dp
}

/** The backdrop glass in this part of the tree samples. Null outside a [GlassScene]. */
val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** False while the enclosing scene is covered or off screen, and its glass should hold still. */
internal val LocalGlassActive = compositionLocalOf { true }

/**
 * Says whether the glass inside [content] can be seen. While it cannot, that glass stops
 * redrawing with the light, as it does in a scene that is not [active][GlassScene]. For
 * part of a scene that is composed but off screen, such as a page beside the current one.
 */
@Composable
fun GlassActivity(active: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGlassActive provides (active && LocalGlassActive.current), content = content)
}

/** True inside a piece of glass. Glass laid on glass has nothing to bend; it is painted directly. */
internal val LocalOnGlass = compositionLocalOf { false }

/**
 * Two layers: [backdrop] is drawn first and recorded; [content] floats above it, and any
 * `GlassSurface` inside [content] refracts that recording.
 *
 * Glass must live in [content], never in [backdrop]. A surface placed inside the thing it
 * samples would have to draw itself to draw itself.
 *
 * Give [modifier] a definite size; both layers fill it.
 *
 * @param frost how far the backdrop is diffused before glass sees it. One value for the
 *   whole scene, so that every piece of glass in it is looking at the same weather.
 * @param refract pass false when the backdrop is nothing but soft light. Bending it would
 *   change nothing anyone could see, so glass over it skips the copy of the backdrop
 *   altogether and is painted in a single step: the same tint, rim and light, at a
 *   fraction of the cost. Leave it true wherever words or pictures pass behind the glass.
 * @param active pass false while this scene cannot be seen. Its glass then stops redrawing
 *   with the light instead of animating for nobody. A scene nested inside another is also
 *   still whenever the outer one is.
 */
@Composable
fun GlassScene(
    modifier: Modifier = Modifier,
    frost: Dp = MellowTheme.glass.regular.blur,
    refract: Boolean = true,
    active: Boolean = true,
    backdrop: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val scene = rememberGraphicsLayer()
    val state = remember(scene) { Backdrop(scene) }
    state.refracts = refract
    state.frost = frost
    val alive = active && LocalGlassActive.current

    Box(modifier) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .onPlaced { state.coordinates = it }
                .drawWithContent {
                    // Record once, then draw the recording. Each piece of glass frosts
                    // its own strip of the same recording when it comes to draw.
                    scene.record { this@drawWithContent.drawContent() }
                    drawLayer(scene)
                },
            content = backdrop,
        )
        CompositionLocalProvider(LocalBackdrop provides state, LocalGlassActive provides alive) {
            content()
        }
    }
}
