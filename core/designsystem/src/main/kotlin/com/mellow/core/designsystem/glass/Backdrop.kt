package com.mellow.core.designsystem.glass

import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import kotlin.math.max
import kotlin.math.min

/**
 * The frost is kept smaller than the scene, because it is about to be blurred anyway and
 * both the blur and everything that reads it then work on a fraction of the pixels. How
 * much smaller depends on the blur: a light frost still shows the shapes behind it, so it
 * is kept at half size (a quarter of the pixels); a heavy one hides them and can drop to
 * quarter size (a sixteenth).
 */
private fun frostDownscale(blurPx: Float): Float = if (blurPx >= HeavyBlurPx) 4f else 2f

private const val HeavyBlurPx = 30f

/**
 * One strip of the scene, frosted: shrunk, blurred, and kept in its own buffer for the
 * glass lying over that strip to read.
 */
internal class FrostBand(
    private val source: GraphicsLayer,
    private val frost: GraphicsLayer,
) {
    /** Where this band's top-left corner is in the scene. */
    private var left = 0f
    private var top = 0f

    /** Scene pixels per frost pixel, on each axis. */
    private var scaleX = 1f
    private var scaleY = 1f

    private var blurRadius = -1f
    private var blur: RenderEffect? = null

    @RequiresApi(Build.VERSION_CODES.S)
    fun DrawScope.recordFrom(scene: GraphicsLayer, region: Rect, blurPx: Float) {
        val downscale = frostDownscale(blurPx)
        val small = IntSize(ceil(region.width / downscale).toInt(), ceil(region.height / downscale).toInt())
        if (small.width <= 0 || small.height <= 0) return
        left = region.left
        top = region.top
        scaleX = region.width / small.width
        scaleY = region.height / small.height

        // This strip of the scene, shrunk. Everything outside the strip is clipped away
        // by the layer's own bounds, so the rest of the page is not drawn a second time.
        source.record(small) {
            scale(1f / scaleX, 1f / scaleY, pivot = Offset.Zero) {
                translate(-region.left, -region.top) { drawLayer(scene) }
            }
        }
        val radius = blurPx / downscale
        if (radius != blurRadius) {
            blurRadius = radius
            blur = if (radius >= 0.5f) BlurEffect(radius, radius, TileMode.Clamp) else null
        }
        source.renderEffect = blur

        // A blur is re-applied every time its layer is drawn. Drawing it into a second,
        // offscreen layer runs it once and keeps the result for every surface to share.
        // With no blur there is nothing to keep, and the extra buffer would be pure cost.
        frost.compositingStrategy = if (blur != null) CompositingStrategy.Offscreen else CompositingStrategy.Auto
        frost.record(small) { drawLayer(source) }
    }

    /**
     * Draws the patch of frost that lies under a surface whose top-left corner is at
     * [origin] in the scene, scaled back up to full size, leaving [inset] pixels of margin.
     */
    fun DrawScope.drawPatch(origin: Offset, inset: Float = 0f) {
        scale(scaleX, scaleY, pivot = Offset.Zero) {
            translate((inset - origin.x + left) / scaleX, (inset - origin.y + top) / scaleY) { drawLayer(frost) }
        }
    }
}

/** A glass surface's standing request for frost: where it was last drawn, and when. */
internal class FrostClaim {
    var needed: Rect = Rect.Zero
    var seenAt = 0L
}

/**
 * What a piece of glass looks through.
 *
 * [scene] is a recording of everything drawn behind the glass. Glass does not look at all
 * of it: a tab bar covers a strip along the bottom of the screen, not the screen. So only
 * the strips that have glass over them are frosted (shrunk, blurred and kept, once per
 * frame, for every surface on that strip to share). Frosting the whole screen for the sake
 * of a strip was costing more than drawing the screen itself.
 *
 * Each surface says where it is every time it draws. The strips follow: they are laid out
 * again when a surface is not covered, and tightened now and then when surfaces have gone.
 */
@Stable
class Backdrop internal constructor(
    internal val scene: GraphicsLayer,
    private val bands: List<FrostBand>,
) {
    internal var coordinates: LayoutCoordinates? = null

    /** False when the scene behind is only soft light: nothing in it would visibly bend, so glass over it is painted directly. */
    internal var refracts = true

    /**
     * The strips being frosted, in scene pixels; `regions[i]` is recorded into `bands[i]`.
     * Snapshot state on purpose: the scene re-records its frost, and every surface redraws
     * against it, in the frame after it changes.
     */
    private var regions: List<Rect> by mutableStateOf(emptyList())

    /** What the bands actually hold right now, which trails [regions] by a frame when it changes. */
    private var recorded: List<Rect> = emptyList()

    private val claims = ArrayList<FrostClaim>()
    private var auditedAt = 0L
    private var blurReach = 0f
    private var grid = 32f
    private var mergeGap = 0f

    internal fun claim(): FrostClaim = FrostClaim().also { claims += it }

    internal fun release(claim: FrostClaim) {
        claims -= claim
    }

    @RequiresApi(Build.VERSION_CODES.S)
    internal fun DrawScope.recordFrost(blurPx: Float) {
        // A blur reads past the edge of what it shows; so must the strip.
        blurReach = blurPx * 2.5f
        mergeGap = MergeGap.toPx()
        grid = Grid.toPx()
        val wanted = regions
        for (index in wanted.indices) {
            with(bands[index]) { recordFrom(scene, wanted[index], blurPx) }
        }
        recorded = wanted
    }

    /**
     * The frost for a surface occupying [bounds] of the scene, or null if no strip covers
     * it yet. In that case one is being arranged, and the surface will be redrawn against
     * it in the next frame.
     *
     * @param reach how far outside its own bounds the surface may read (the bend at its rim).
     */
    internal fun bandFor(claim: FrostClaim, bounds: Rect, reach: Float): FrostBand? {
        val margin = reach + blurReach
        val needed = Rect(bounds.left - margin, bounds.top - margin, bounds.right + margin, bounds.bottom + margin)
        val now = SystemClock.uptimeMillis()
        claim.needed = needed
        claim.seenAt = now

        // Reading this is also what gets the surface redrawn when the strips are laid out again.
        val wanted = regions
        val planned = wanted.any { it.covers(needed) }
        if (!planned || now - auditedAt > AuditMillis) {
            auditedAt = now
            val better = plan(now)
            // Lay the strips out again if this surface is uncovered, or if surfaces have
            // left and the strips are now much bigger than anyone needs.
            if ((!planned || area(wanted) > area(better) * WastefulRatio) && better != wanted) regions = better
        }
        val index = recorded.indexOfFirst { it.covers(needed) }
        return if (index >= 0) bands[index] else null
    }

    private fun Rect.covers(other: Rect) = left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom

    /** One strip per cluster of surfaces: surfaces near each other vertically share a strip. */
    private fun plan(now: Long): List<Rect> {
        val live = claims.filter { now - it.seenAt < LiveMillis }.map { it.needed }.sortedBy { it.top }
        val groups = ArrayList<Rect>()
        for (rect in live) {
            val last = groups.lastOrNull()
            if (last != null && rect.top <= last.bottom + mergeGap) {
                groups[groups.lastIndex] = union(last, rect)
            } else {
                groups += rect
            }
        }
        // More clusters than buffers: join the two that are closest together.
        while (groups.size > bands.size) {
            var closest = 0
            for (i in 1 until groups.lastIndex) {
                if (groups[i + 1].top - groups[i].bottom < groups[closest + 1].top - groups[closest].bottom) closest = i
            }
            groups[closest] = union(groups[closest], groups.removeAt(closest + 1))
        }
        // Snapped so that a strip keeps its size while the surface over it moves: its
        // corner lands on a grid, and its sides are whole grid steps long.
        return groups.map { rect ->
            val left = floor(rect.left / grid) * grid
            val top = floor(rect.top / grid) * grid
            Rect(left, top, left + ceil((rect.width + grid) / grid) * grid, top + ceil((rect.height + grid) / grid) * grid)
        }
    }

    private fun union(a: Rect, b: Rect) = Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))

    private fun area(rects: List<Rect>): Float = rects.fold(0f) { sum, rect -> sum + rect.width * rect.height }

    private companion object {
        /** A surface that has not drawn for this long is taken to be gone. */
        const val LiveMillis = 400L
        const val AuditMillis = 600L
        const val WastefulRatio = 1.5f

        /** Surfaces closer together than this share a strip rather than getting one each. */
        val MergeGap = 56.dp
        val Grid = 12.dp
    }
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
 *   whole scene, because each strip of it is blurred once and shared.
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
    // Three strips is enough for a bar at the top, a bar at the bottom and something
    // between them. Each is a pair of layers; an unused pair costs nothing.
    val layers = List(BandCount * 2) { rememberGraphicsLayer() }
    val state = remember(scene) { Backdrop(scene, List(BandCount) { FrostBand(layers[it * 2], layers[it * 2 + 1]) }) }
    state.refracts = refract
    val alive = active && LocalGlassActive.current

    Box(modifier) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .onPlaced { state.coordinates = it }
                .drawWithContent {
                    // Record once, then draw the recording. The frost is rebuilt from the
                    // same recording, here, whenever the strips under glass change.
                    scene.record { this@drawWithContent.drawContent() }
                    drawLayer(scene)
                    if (refract && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        with(state) { recordFrost(frost.toPx()) }
                    }
                },
            content = backdrop,
        )
        CompositionLocalProvider(LocalBackdrop provides state, LocalGlassActive provides alive) {
            content()
        }
    }
}

private const val BandCount = 3
