package com.mellow.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mellow.core.designsystem.icon.MellowIcons
import com.mellow.core.designsystem.theme.ContinuousCornerShape
import com.mellow.core.designsystem.theme.MellowTheme
import com.mellow.core.designsystem.theme.asShadowCaster

/**
 * The cover of something made of several albums, such as a playlist: four of their covers
 * in a square once there are four to show, one cover before that, and a plain tile when
 * there are none.
 *
 * @param uris covers to draw from, in order. Only the first four are used.
 */
@Composable
fun CoverMosaic(
    uris: List<String>,
    modifier: Modifier = Modifier,
    radius: Dp = MellowTheme.radii.cover,
    elevation: Dp = 0.dp,
) {
    val colors = MellowTheme.colors
    val shape = remember(radius) { ContinuousCornerShape(radius) }

    if (uris.size < 4) {
        CoverArt(uris.firstOrNull(), modifier, shape = shape, elevation = elevation, emptyIcon = MellowIcons.EmptyPlaylist)
        return
    }

    // Four square images under one rounded clip. The clip is the plain rounded rectangle
    // the GPU does for free, the same stand-in the shadows use, rather than the exact
    // continuous curve, which would have to be masked on the CPU every frame.
    val outline = shape.asShadowCaster()
    Column(
        modifier
            .shadow(elevation, outline, clip = false, ambientColor = colors.shadow, spotColor = colors.shadow)
            .clip(outline),
    ) {
        for (row in 0..1) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                for (column in 0..1) {
                    CoverArt(uris[row * 2 + column], Modifier.weight(1f).fillMaxHeight(), shape = RectangleShape)
                }
            }
        }
    }
}
