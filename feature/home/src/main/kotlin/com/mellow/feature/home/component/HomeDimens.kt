package com.mellow.feature.home.component

import androidx.compose.ui.unit.dp

/**
 * The measurements of the Home page, in one place. The real components and their
 * skeletons both read these, so a placeholder can never drift out of line with the thing
 * it stands in for. (Rows keep theirs in the design system's `TrackRowDefaults`.)
 */
internal object HomeDimens {
    const val FeaturedAspect = 1.16f
    val FeaturedStripInset = 10.dp
    val FeaturedStripHeight = 76.dp
    val FeaturedPlayButton = 52.dp

    val ShelfCover = 152.dp
    val ShelfVerticalPadding = 14.dp
}
