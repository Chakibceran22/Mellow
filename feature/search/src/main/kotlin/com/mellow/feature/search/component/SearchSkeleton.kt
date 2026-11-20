package com.mellow.feature.search.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mellow.core.designsystem.component.SkeletonTextLine
import com.mellow.core.designsystem.component.TrackRowSkeleton
import com.mellow.core.designsystem.theme.MellowTheme

/**
 * The page while the first answer to a query is on its way: a section title and a run of
 * rows, which is the shape most answers take. Songs that are found land where these were.
 */
@Composable
internal fun SearchSkeleton(modifier: Modifier = Modifier) {
    val spacing = MellowTheme.spacing
    Column(modifier.semantics { contentDescription = "Searching your music" }) {
        // The "Songs" title
        SkeletonTextLine(
            width = 84.dp,
            lineHeight = 30.dp,
            ink = 20.dp,
            modifier = Modifier.padding(horizontal = spacing.gutter).padding(bottom = spacing.xs),
        )
        repeat(6) { index -> TrackRowSkeleton(index = index) }
    }
}
