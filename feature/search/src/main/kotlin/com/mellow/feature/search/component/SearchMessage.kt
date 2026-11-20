package com.mellow.feature.search.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.mellow.core.designsystem.component.Text
import com.mellow.core.designsystem.theme.MellowTheme

/**
 * What the page says when it has no results to show: a heading and one plain sentence.
 * Used before anything is typed, when nothing matched, and when the library cannot be read.
 *
 * @param titleStyle the page's own title is the display size; a remark about one search is smaller.
 */
@Composable
internal fun SearchMessage(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = MellowTheme.type.display,
) {
    val spacing = MellowTheme.spacing
    Column(modifier.padding(horizontal = spacing.gutter)) {
        Text(title, style = titleStyle, maxLines = 3, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(spacing.sm))
        Text(message, color = MellowTheme.colors.inkMuted, modifier = Modifier.widthIn(max = 320.dp))
    }
}
