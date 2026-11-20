package com.mellow.core.designsystem.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mellow.core.designsystem.glass.GlassShape
import com.mellow.core.designsystem.glass.GlassSurface
import com.mellow.core.designsystem.theme.MellowTheme

/** What a [Button] is for, which decides the glass it is made of. */
enum class ButtonKind {
    /** The main thing to do: green glass. One per screen or sheet. */
    Primary,

    /** The way out, or a second choice: milky glass with no colour of its own. */
    Quiet,

    /** Deletes something for good: red glass. Only ever on a confirmation. */
    Destructive,
}

/**
 * A button with words on it, made of tinted glass. It swells under the finger like every
 * other piece of glass, and its rim catches the same light.
 *
 * Being glass, it must have a `GlassScene` somewhere above it. Every page and every sheet
 * in the app already does.
 *
 * @param text what pressing it does, starting with a verb: "Allow access to music".
 */
@Composable
fun Button(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    icon: ImageVector? = null,
) {
    val colors = MellowTheme.colors
    val glass = MellowTheme.glass
    val style = when (kind) {
        ButtonKind.Primary -> glass.accent
        ButtonKind.Quiet -> glass.thick
        ButtonKind.Destructive -> glass.danger
    }
    val content = when (kind) {
        ButtonKind.Primary -> colors.onAccent
        ButtonKind.Quiet -> colors.ink
        ButtonKind.Destructive -> colors.onDanger
    }

    GlassSurface(
        modifier = modifier.heightIn(min = ButtonHeight),
        shape = GlassShape.Capsule,
        style = style,
        onClick = onClick,
        role = Role.Button,
        pressScale = 1.04f,
        contentColor = content,
    ) {
        Row(Modifier.padding(horizontal = 26.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, size = 20.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(text, style = MellowTheme.type.headline, maxLines = 1)
        }
    }
}

val ButtonHeight = 52.dp
