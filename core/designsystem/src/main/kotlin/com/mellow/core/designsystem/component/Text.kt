package com.mellow.core.designsystem.component

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.mellow.core.designsystem.theme.MellowTheme

/** The colour text and icons take when none is given. Surfaces set it for their content. */
val LocalContentColor = compositionLocalOf { Color.Unspecified }

/** Text in the Mellow type scale. Pass one of `MellowTheme.type`'s styles. */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MellowTheme.type.body,
    color: Color = Color.Unspecified,
    textAlign: TextAlign = TextAlign.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
) {
    val resolved = if (color.isSpecified) color else LocalContentColor.current
    BasicText(
        text = text,
        modifier = modifier,
        style = style.merge(color = resolved, textAlign = textAlign),
        overflow = overflow,
        maxLines = maxLines,
    )
}
