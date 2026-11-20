package com.mellow.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mellow.core.designsystem.glass.GlassShape
import com.mellow.core.designsystem.glass.GlassSurface

/**
 * A round glass button holding one icon. For controls that float over content.
 *
 * @param contentDescription says what the button does, as a verb: "Close player".
 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    iconSize: Dp = 20.dp,
) {
    GlassSurface(
        modifier = modifier.size(size),
        shape = GlassShape.Capsule,
        onClick = onClick,
        role = Role.Button,
        pressScale = 1.10f,
    ) {
        Icon(icon, contentDescription, size = iconSize)
    }
}

/**
 * A bare icon with a comfortable touch target. For controls that already sit on a
 * surface, where a second container would be glass on glass.
 *
 * @param touchTarget the tappable square around the icon; never below 44dp.
 */
@Composable
fun IconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    iconSize: Dp = 24.dp,
    touchTarget: Dp = 48.dp,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(touchTarget)
            .pressable(onClick = onClick, enabled = enabled, pressedScale = 0.84f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, size = iconSize)
    }
}
