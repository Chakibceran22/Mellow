package com.mellow.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mellow.core.designsystem.R

/**
 * Figtree, one variable file, cut at the five weights the scale uses. Its round, open
 * shapes echo the disc in the Mellow mark; it carries every piece of text in the app.
 */
@OptIn(ExperimentalTextApi::class)
private fun figtree(weight: Int) = Font(
    resId = R.font.figtree,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Figtree = FontFamily(figtree(400), figtree(500), figtree(600), figtree(700), figtree(800))

/**
 * The type scale. Seven steps, each with one job. Large sizes tighten their tracking and
 * small sizes open it, so every step reads as the same voice.
 */
@Immutable
data class MellowType(
    /** The one large title at the top of a screen. */
    val display: TextStyle,
    /** Section headings and the track title on the player. */
    val title: TextStyle,
    /** Names of things in lists and cards. */
    val headline: TextStyle,
    val body: TextStyle,
    /** Supporting lines under a headline. */
    val callout: TextStyle,
    val caption: TextStyle,
    /** Tab labels and other text inside small controls. */
    val label: TextStyle,
)

private fun style(size: Int, line: Int, weight: Int, tracking: Double) = TextStyle(
    fontFamily = Figtree,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

internal val DefaultType = MellowType(
    display = style(size = 36, line = 40, weight = 800, tracking = -0.9),
    title = style(size = 24, line = 30, weight = 700, tracking = -0.4),
    headline = style(size = 17, line = 22, weight = 600, tracking = -0.2),
    body = style(size = 16, line = 23, weight = 500, tracking = -0.1),
    callout = style(size = 15, line = 20, weight = 500, tracking = -0.05),
    caption = style(size = 13, line = 18, weight = 500, tracking = 0.0),
    label = style(size = 11, line = 14, weight = 600, tracking = 0.1),
)

/** Figures that all take the same width, so a running clock does not jitter. */
val TextStyle.tabular: TextStyle get() = copy(fontFeatureSettings = "tnum")
