package com.mellow.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import com.mellow.core.designsystem.component.LocalContentColor
import com.mellow.core.designsystem.glass.DarkGlass
import com.mellow.core.designsystem.glass.GlassStyles
import com.mellow.core.designsystem.glass.LightGlass
import com.mellow.core.designsystem.light.DarkRoom
import com.mellow.core.designsystem.light.LightRoom
import com.mellow.core.designsystem.light.LightingScheme
import com.mellow.core.designsystem.light.LocalLighting
import com.mellow.core.designsystem.light.rememberLightingEngine

private val LocalColors = staticCompositionLocalOf { LightColors }
private val LocalType = staticCompositionLocalOf { DefaultType }
private val LocalRadii = staticCompositionLocalOf { MellowRadii() }
private val LocalSpacing = staticCompositionLocalOf { MellowSpacing() }
private val LocalGlass = staticCompositionLocalOf { LightGlass }
private val LocalLightingScheme = staticCompositionLocalOf { LightRoom }

/**
 * The Mellow design system, in one place. Read tokens through this object:
 * `MellowTheme.colors.ink`, `MellowTheme.type.title`, `MellowTheme.glass.regular`.
 */
object MellowTheme {
    val colors: MellowColors
        @Composable @ReadOnlyComposable get() = LocalColors.current
    val type: MellowType
        @Composable @ReadOnlyComposable get() = LocalType.current
    val radii: MellowRadii
        @Composable @ReadOnlyComposable get() = LocalRadii.current
    val spacing: MellowSpacing
        @Composable @ReadOnlyComposable get() = LocalSpacing.current
    val glass: GlassStyles
        @Composable @ReadOnlyComposable get() = LocalGlass.current
    val lighting: LightingScheme
        @Composable @ReadOnlyComposable get() = LocalLightingScheme.current
}

/**
 * Provides the tokens for the current theme and starts the lighting engine. Everything
 * drawn by the design system must sit inside this.
 */
@Composable
fun MellowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val engine = rememberLightingEngine()

    CompositionLocalProvider(
        LocalColors provides colors,
        LocalGlass provides if (darkTheme) DarkGlass else LightGlass,
        LocalLightingScheme provides if (darkTheme) DarkRoom else LightRoom,
        LocalLighting provides engine,
        LocalContentColor provides colors.ink,
        content = content,
    )
}
