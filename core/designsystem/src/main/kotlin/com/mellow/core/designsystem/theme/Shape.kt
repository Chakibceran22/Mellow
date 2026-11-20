package com.mellow.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The exponent of the corner curve `x^n + y^n = r^n`. At 2 it is a plain circular arc;
 * above 2 the curve eases into the straight edges instead of meeting them abruptly, which
 * is what makes a corner read as continuous. The glass shader uses the same number, so a
 * glass surface and an opaque one have identical silhouettes.
 */
const val ContinuousCornerPower = 2.6f

/**
 * A rectangle whose corners follow a superellipse rather than a circle, so curvature
 * builds gradually along the edge.
 */
@Immutable
class ContinuousCornerShape(val radius: Dp) : Shape {

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = min(with(density) { radius.toPx() }, min(size.width, size.height) / 2f)
        if (r <= 0f) return Outline.Rectangle(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))

        val w = size.width
        val h = size.height
        val exponent = 2.0 / ContinuousCornerPower
        val path = Path()

        // One quarter of the curve, walked from the vertical tangent to the horizontal one.
        // (cx, cy) is the corner's centre; (sx, sy) points from that centre to the corner.
        fun corner(cx: Float, cy: Float, sx: Int, sy: Int, fromHorizontalEdge: Boolean) {
            for (step in 1..CornerSegments) {
                val t = step.toDouble() / CornerSegments
                val angle = (if (fromHorizontalEdge) 1.0 - t else t) * PI / 2.0
                val x = r * cos(angle).coerceAtLeast(0.0).pow(exponent).toFloat()
                val y = r * sin(angle).coerceAtLeast(0.0).pow(exponent).toFloat()
                path.lineTo(cx + sx * x, cy + sy * y)
            }
        }

        path.moveTo(r, 0f)
        path.lineTo(w - r, 0f)
        corner(w - r, r, sx = 1, sy = -1, fromHorizontalEdge = true)
        path.lineTo(w, h - r)
        corner(w - r, h - r, sx = 1, sy = 1, fromHorizontalEdge = false)
        path.lineTo(r, h)
        corner(r, h - r, sx = -1, sy = 1, fromHorizontalEdge = true)
        path.lineTo(0f, r)
        corner(r, r, sx = -1, sy = -1, fromHorizontalEdge = false)
        path.close()
        return Outline.Generic(path)
    }

    override fun equals(other: Any?): Boolean = other is ContinuousCornerShape && other.radius == radius

    override fun hashCode(): Int = radius.hashCode()

    private companion object {
        const val CornerSegments = 14
    }
}

/**
 * The shape to cast a shadow from. A continuous corner has no fast path for shadows, and a
 * shadow is blurred far past the point where its corner curve could be told apart, so it
 * is cast from the plain rounded rectangle that sits closest to the real outline.
 */
fun Shape.asShadowCaster(): Shape =
    if (this is ContinuousCornerShape) RoundedCornerShape(radius * 0.86f) else this

/**
 * Corner radii. They step down as surfaces nest: a child's radius is its parent's radius
 * minus the gap between them ([concentric]), so inner and outer curves stay parallel.
 */
@Immutable
data class MellowRadii(
    val thumb: Dp = 12.dp,
    val cover: Dp = 22.dp,
    val card: Dp = 30.dp,
    val panel: Dp = 36.dp,
    val sheet: Dp = 44.dp,
) {
    /** The radius for something inset by [inset] inside a surface of radius [outer]. */
    fun concentric(outer: Dp, inset: Dp): Dp = (outer - inset).coerceAtLeast(6.dp)
}
