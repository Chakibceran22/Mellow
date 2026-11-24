package com.mellow.core.designsystem.component

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.pxOrElse
import coil3.transform.Transformation
import com.mellow.core.designsystem.icon.MellowIcons
import com.mellow.core.designsystem.theme.ContinuousCornerShape
import com.mellow.core.designsystem.theme.MellowTheme
import com.mellow.core.designsystem.theme.asShadowCaster
import kotlin.math.max

/**
 * A cover image read from this device.
 *
 * Continuous corners are not clipped on screen. Android can only clip to an arbitrary
 * curve by rebuilding a mask on the CPU every frame, for every cover, which was costing
 * more than the rest of the app's drawing put together. Instead the corners are cut into
 * the image itself, once, on a background thread as it is decoded. What reaches the screen
 * is an ordinary bitmap that already has the right shape, and drawing it costs the same as
 * drawing a square one. The shaped copy is kept in the image cache, so a cover scrolled
 * back into view is not cut again.
 *
 * Until the image arrives, a [skeleton] of the same shape holds its place. Plenty of music
 * has no cover at all; that is only known once the read fails, and the skeleton then gives
 * way to a plain tile of the same shape with a note on it, so a coverless track looks
 * finished rather than forever loading.
 *
 * @param uri where the image lives on this device. Null is treated as having no cover.
 * @param elevation lifts the cover off the surface with a soft shadow.
 * @param emptyIcon what the plain tile shows when there is no cover.
 * @param onNoCover called once it is known that there is no cover to show.
 */
@Composable
fun CoverArt(
    uri: String?,
    modifier: Modifier = Modifier,
    shape: Shape = ContinuousCornerShape(MellowTheme.radii.cover),
    elevation: Dp = 0.dp,
    contentDescription: String? = null,
    emptyIcon: ImageVector = MellowIcons.NoCover,
    onNoCover: (() -> Unit)? = null,
) {
    val colors = MellowTheme.colors
    val context = LocalPlatformContext.current
    val density = LocalDensity.current
    val continuous = shape as? ContinuousCornerShape

    val request = remember(uri, continuous, density) {
        ImageRequest.Builder(context)
            .data(uri)
            .apply {
                if (continuous != null) {
                    transformations(ContinuousCornerTransformation(with(density) { continuous.radius.toPx() }))
                }
            }
            .build()
    }
    // Loading, loaded, or known to have no cover.
    var settled by remember(uri) { mutableStateOf(false) }
    val glyph = rememberVectorPainter(emptyIcon)
    val noCover = remember(shape, glyph, colors) { NoCoverPainter(shape, colors.surface, glyph, colors.inkFaint) }

    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        error = noCover,
        onSuccess = { settled = true },
        onError = {
            settled = true
            onNoCover?.invoke()
        },
        modifier = modifier
            // Only something solid casts a shadow; under a see-through placeholder it would show as a stain.
            .shadow(if (settled) elevation else 0.dp, shape.asShadowCaster(), clip = false, ambientColor = colors.shadow, spotColor = colors.shadow)
            // Rectangles, rounded rectangles and circles are clipped by the GPU for free.
            .then(if (continuous == null) Modifier.clip(shape) else Modifier)
            .skeleton(shape, visible = !settled),
    )
}

/** What a cover looks like when the music has none: a flat tile in the cover's shape, with a note. */
private class NoCoverPainter(
    private val shape: Shape,
    private val fill: Color,
    private val glyph: VectorPainter,
    private val ink: Color,
) : Painter() {

    private val tint = ColorFilter.tint(ink)

    // No size of its own: it fills whatever space the cover was given.
    override val intrinsicSize: Size = Size.Unspecified

    override fun DrawScope.onDraw() {
        drawOutline(shape.createOutline(size, layoutDirection, this), fill)
        val side = size.minDimension * GlyphShare
        translate((size.width - side) / 2f, (size.height - side) / 2f) {
            with(glyph) { draw(Size(side, side), colorFilter = tint) }
        }
    }

    private companion object {
        const val GlyphShare = 0.38f
    }
}

/**
 * Crops an image to fill the requested size and cuts continuous corners into it, giving a
 * bitmap that is transparent outside the shape.
 */
private class ContinuousCornerTransformation(private val radiusPx: Float) : Transformation() {

    override val cacheKey: String = "continuous-corners:$radiusPx"

    override suspend fun transform(input: Bitmap, size: coil3.size.Size): Bitmap {
        val width = size.width.pxOrElse { input.width }
        val height = size.height.pxOrElse { input.height }
        val output = createBitmap(width, height)

        // Scale to cover the target and centre, exactly as ContentScale.Crop would.
        val scale = max(width / input.width.toFloat(), height / input.height.toFloat())
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate((width - input.width * scale) / 2f, (height - input.height * scale) / 2f)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(input, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
        }

        // The same outline the rest of the design system uses, measured in pixels.
        val outline = ContinuousCornerShape(radiusPx.dp)
            .createOutline(Size(width.toFloat(), height.toFloat()), LayoutDirection.Ltr, Density(1f))
        val canvas = Canvas(output)
        when (outline) {
            is Outline.Generic -> canvas.drawPath(outline.path.asAndroidPath(), paint)
            else -> canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
        return output
    }

    override fun equals(other: Any?): Boolean = other is ContinuousCornerTransformation && other.radiusPx == radiusPx

    override fun hashCode(): Int = radiusPx.hashCode()
}
