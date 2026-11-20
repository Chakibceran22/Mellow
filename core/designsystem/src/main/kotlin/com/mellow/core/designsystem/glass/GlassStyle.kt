package com.mellow.core.designsystem.glass

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mellow.core.designsystem.theme.ContinuousCornerShape
import com.mellow.core.designsystem.theme.MellowPalette

/**
 * The silhouette of a piece of glass. Two families only: a capsule (which is a circle
 * when the surface is square) for controls, and a continuous-corner rectangle for panels.
 */
@Immutable
sealed interface GlassShape {
    data object Capsule : GlassShape

    data class Continuous(val radius: Dp) : GlassShape
}

internal fun GlassShape.asShape(): Shape = when (this) {
    GlassShape.Capsule -> CircleShape
    is GlassShape.Continuous -> ContinuousCornerShape(radius)
}

/**
 * The optical recipe for a piece of glass.
 *
 * @property blur how far the backdrop is diffused before it is seen through the glass.
 *   Applied per scene, not per surface: pass it to `GlassScene(frost = ...)`.
 * @property tint colour laid into the body of the glass; its alpha is how milky it is.
 * @property saturation boost applied to what shows through, so colour survives the blur.
 * @property bezel width of the curved rim. The face inside it is flat.
 * @property refraction how far the rim bends the backdrop, like the edge of a lens.
 * @property rim strength of the line of light along the edge.
 * @property sheen strength of the broader glow on the curve of the rim.
 * @property fallback opaque-enough fill for devices that cannot blur.
 */
@Immutable
data class GlassStyle(
    val blur: Dp,
    val tint: Color,
    val saturation: Float,
    val bezel: Dp,
    val refraction: Dp,
    val rim: Float,
    val sheen: Float,
    val elevation: Dp,
    val shadow: Color,
    val fallback: Color,
    val hairline: Color,
)

/** The glass variants a theme offers. */
@Immutable
data class GlassStyles(
    /** Controls and bars floating over a screen. */
    val regular: GlassStyle,
    /** Glass laid directly over imagery, where more of what is beneath should read. */
    val clear: GlassStyle,
    /** Sheets and other large panels that hold a lot of text: milkier, so the page behind stays behind. */
    val thick: GlassStyle,
    /** Green glass, for the main button on a screen. Carries `onAccent` text. */
    val accent: GlassStyle,
    /** Red glass, for a button that deletes something for good. Carries `onDanger` text. */
    val danger: GlassStyle,
)

/*
 * The look: clear glass. What is behind it stays recognisable and bends at the rim, with
 * just enough frost and tint that text on the glass is never fighting text under it.
 */
internal val LightGlass = GlassStyles(
    regular = GlassStyle(
        blur = 6.dp,
        tint = MellowPalette.Frost.copy(alpha = 0.24f),
        saturation = 1.50f,
        bezel = 18.dp,
        refraction = 11.dp,
        rim = 1.0f,
        sheen = 0.38f,
        elevation = 14.dp,
        shadow = MellowPalette.Pine.copy(alpha = 0.70f),
        fallback = MellowPalette.Frost.copy(alpha = 0.94f),
        hairline = Color.White.copy(alpha = 0.70f),
    ),
    clear = GlassStyle(
        blur = 4.dp,
        tint = MellowPalette.Frost.copy(alpha = 0.18f),
        saturation = 1.45f,
        bezel = 16.dp,
        refraction = 11.dp,
        rim = 1.0f,
        sheen = 0.38f,
        elevation = 8.dp,
        shadow = MellowPalette.Pine.copy(alpha = 0.60f),
        fallback = MellowPalette.Frost.copy(alpha = 0.90f),
        hairline = Color.White.copy(alpha = 0.70f),
    ),
    thick = GlassStyle(
        blur = 6.dp,
        tint = MellowPalette.Frost.copy(alpha = 0.76f),
        saturation = 1.40f,
        bezel = 22.dp,
        refraction = 12.dp,
        rim = 1.0f,
        sheen = 0.38f,
        elevation = 24.dp,
        shadow = MellowPalette.Pine.copy(alpha = 0.80f),
        fallback = MellowPalette.Frost.copy(alpha = 0.97f),
        hairline = Color.White.copy(alpha = 0.70f),
    ),
    accent = GlassStyle(
        blur = 6.dp,
        // Dense enough to carry pale text, thin enough that the room still shows through.
        tint = MellowPalette.Spruce.copy(alpha = 0.78f),
        saturation = 1.50f,
        bezel = 16.dp,
        refraction = 10.dp,
        rim = 0.90f,
        sheen = 0.50f,
        elevation = 10.dp,
        shadow = MellowPalette.Pine.copy(alpha = 0.75f),
        fallback = MellowPalette.Spruce,
        hairline = Color.White.copy(alpha = 0.40f),
    ),
    danger = GlassStyle(
        blur = 6.dp,
        tint = MellowPalette.Brick.copy(alpha = 0.80f),
        saturation = 1.40f,
        bezel = 16.dp,
        refraction = 10.dp,
        rim = 0.90f,
        sheen = 0.50f,
        elevation = 10.dp,
        shadow = MellowPalette.Pine.copy(alpha = 0.75f),
        fallback = MellowPalette.Brick,
        hairline = Color.White.copy(alpha = 0.40f),
    ),
)

internal val DarkGlass = GlassStyles(
    regular = GlassStyle(
        blur = 6.dp,
        tint = MellowPalette.Moss.copy(alpha = 0.36f),
        saturation = 1.50f,
        bezel = 18.dp,
        refraction = 11.dp,
        rim = 0.78f,
        sheen = 0.28f,
        elevation = 16.dp,
        shadow = Color.Black.copy(alpha = 0.90f),
        fallback = MellowPalette.Moss.copy(alpha = 0.95f),
        hairline = MellowPalette.Cream.copy(alpha = 0.16f),
    ),
    clear = GlassStyle(
        blur = 4.dp,
        // Denser than the rest: this one sits on bright cover art and carries cream text.
        tint = MellowPalette.Night.copy(alpha = 0.52f),
        saturation = 1.45f,
        bezel = 16.dp,
        refraction = 11.dp,
        rim = 0.78f,
        sheen = 0.28f,
        elevation = 8.dp,
        shadow = Color.Black.copy(alpha = 0.80f),
        fallback = MellowPalette.Moss.copy(alpha = 0.92f),
        hairline = MellowPalette.Cream.copy(alpha = 0.16f),
    ),
    thick = GlassStyle(
        blur = 6.dp,
        tint = MellowPalette.Moss.copy(alpha = 0.82f),
        saturation = 1.40f,
        bezel = 22.dp,
        refraction = 12.dp,
        rim = 0.78f,
        sheen = 0.28f,
        elevation = 24.dp,
        shadow = Color.Black.copy(alpha = 0.90f),
        fallback = MellowPalette.Moss.copy(alpha = 0.98f),
        hairline = MellowPalette.Cream.copy(alpha = 0.16f),
    ),
    accent = GlassStyle(
        blur = 6.dp,
        tint = MellowPalette.Mint.copy(alpha = 0.72f),
        saturation = 1.50f,
        bezel = 16.dp,
        refraction = 10.dp,
        rim = 0.85f,
        sheen = 0.40f,
        elevation = 10.dp,
        shadow = Color.Black.copy(alpha = 0.85f),
        fallback = MellowPalette.Mint,
        hairline = Color.White.copy(alpha = 0.30f),
    ),
    danger = GlassStyle(
        blur = 6.dp,
        tint = MellowPalette.Coral.copy(alpha = 0.76f),
        saturation = 1.40f,
        bezel = 16.dp,
        refraction = 10.dp,
        rim = 0.85f,
        sheen = 0.40f,
        elevation = 10.dp,
        shadow = Color.Black.copy(alpha = 0.85f),
        fallback = MellowPalette.Coral,
        hairline = Color.White.copy(alpha = 0.30f),
    ),
)
