package com.mellow.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Raw tones. The first four are lifted straight from the Mellow mark; the rest walk the
 * same green hue lighter or darker so the whole app stays inside one family.
 *
 * Screens never read these. They read [MellowColors], which gives each tone a job.
 */
object MellowPalette {
    val Cream = Color(0xFFE6F1E0)
    val Sage = Color(0xFFB1D3BA)
    val Mint = Color(0xFF89BEA4)
    val Teal = Color(0xFF649288)

    val Frost = Color(0xFFF6FAF2)
    val Dew = Color(0xFFCDE2CD)
    val Spruce = Color(0xFF2F5D52)
    val Pine = Color(0xFF16302A)
    val Moss = Color(0xFF15241F)
    val Night = Color(0xFF0C1714)
    val Abyss = Color(0xFF060D0B)

    // The one family outside the greens: for things that cannot be undone.
    val Brick = Color(0xFFB4483F)
    val Coral = Color(0xFFE8857A)
}

/** Semantic colour roles. Every fill in the app is one flat colour from this set. */
@Immutable
data class MellowColors(
    val isDark: Boolean,
    /** The base of every screen, before any light falls on it. */
    val canvas: Color,
    /** Opaque raised content, such as cards. */
    val surface: Color,
    /** Primary text and icons. */
    val ink: Color,
    /** Secondary text. */
    val inkMuted: Color,
    /** Tertiary text, placeholders, resting controls. */
    val inkFaint: Color,
    /** The one interactive colour: active state, primary buttons, progress. */
    val accent: Color,
    val onAccent: Color,
    /** Destructive actions: deleting something for good. Used nowhere else. */
    val danger: Color,
    val onDanger: Color,
    /** Pressed-state wash and quiet fills behind selected items. */
    val wash: Color,
    /** Placeholder blocks, at their brightest. They dim and brighten as light passes. */
    val skeleton: Color,
    val hairline: Color,
    val shadow: Color,
    /** Laid over the screen behind a modal, so what is in front reads as in front. */
    val scrim: Color,
)

internal val LightColors = MellowColors(
    isDark = false,
    canvas = MellowPalette.Cream,
    surface = MellowPalette.Frost,
    ink = MellowPalette.Pine,
    inkMuted = MellowPalette.Pine.copy(alpha = 0.66f),
    inkFaint = MellowPalette.Pine.copy(alpha = 0.40f),
    accent = MellowPalette.Spruce,
    onAccent = MellowPalette.Cream,
    danger = MellowPalette.Brick,
    onDanger = MellowPalette.Frost,
    wash = MellowPalette.Pine.copy(alpha = 0.07f),
    skeleton = MellowPalette.Pine.copy(alpha = 0.15f),
    hairline = MellowPalette.Pine.copy(alpha = 0.10f),
    shadow = MellowPalette.Pine.copy(alpha = 0.85f),
    scrim = MellowPalette.Pine.copy(alpha = 0.34f),
)

internal val DarkColors = MellowColors(
    isDark = true,
    canvas = MellowPalette.Night,
    surface = MellowPalette.Moss,
    ink = MellowPalette.Cream,
    inkMuted = MellowPalette.Cream.copy(alpha = 0.66f),
    inkFaint = MellowPalette.Cream.copy(alpha = 0.40f),
    accent = MellowPalette.Mint,
    onAccent = MellowPalette.Night,
    danger = MellowPalette.Coral,
    onDanger = MellowPalette.Night,
    wash = MellowPalette.Cream.copy(alpha = 0.08f),
    skeleton = MellowPalette.Cream.copy(alpha = 0.17f),
    hairline = MellowPalette.Cream.copy(alpha = 0.10f),
    shadow = Color.Black,
    scrim = Color.Black.copy(alpha = 0.52f),
)
