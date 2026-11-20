package com.mellow.core.designsystem.light

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.mellow.core.designsystem.theme.MellowPalette

/**
 * The materials and lamps of the room, per theme. The engine decides where the lamps are;
 * this decides what they look like when they land.
 *
 * Every colour here is flat. Tone only varies on screen because light falls unevenly on a
 * surface that is not flat, which is the whole point of having an engine.
 */
@Immutable
data class LightingScheme(
    /** The surface where no light reaches it. */
    val canvas: Color,
    /** What a slope turns toward when it faces the key light. */
    val lift: Color,
    /** What a slope falls toward when it turns away. */
    val shade: Color,
    /** The three moving lamps. The first one takes on the colour of the playing track. */
    val lamps: List<Color>,
    val lampStrength: Float,
    /** Colour and strength of the glints where the surface mirrors a lamp. */
    val specular: Color,
    val specularStrength: Float,
    /** How pronounced the folds of the surface are. */
    val relief: Float,
    /** Film grain, in 0..1 colour units. Breaks up banding in slow tonal ramps. */
    val grain: Float,
)

internal val LightRoom = LightingScheme(
    canvas = MellowPalette.Cream,
    lift = MellowPalette.Frost,
    shade = MellowPalette.Dew,
    lamps = listOf(MellowPalette.Sage, MellowPalette.Mint, MellowPalette.Frost),
    lampStrength = 0.78f,
    specular = Color.White,
    specularStrength = 0.20f,
    relief = 0.85f,
    grain = 0.012f,
)

internal val DarkRoom = LightingScheme(
    canvas = MellowPalette.Night,
    lift = MellowPalette.Moss,
    shade = MellowPalette.Abyss,
    lamps = listOf(MellowPalette.Teal, MellowPalette.Spruce, MellowPalette.Mint),
    lampStrength = 0.50f,
    specular = MellowPalette.Sage,
    specularStrength = 0.13f,
    relief = 0.95f,
    grain = 0.016f,
)
