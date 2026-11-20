package com.mellow.feature.home.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mellow.core.designsystem.component.Skeleton
import com.mellow.core.designsystem.component.SkeletonTextLine
import com.mellow.core.designsystem.component.TrackRowSkeleton
import com.mellow.core.designsystem.glass.GlassScene
import com.mellow.core.designsystem.glass.GlassShape
import com.mellow.core.designsystem.glass.GlassSurface
import com.mellow.core.designsystem.theme.ContinuousCornerShape
import com.mellow.core.designsystem.theme.MellowTheme
import com.mellow.core.designsystem.theme.asShadowCaster

/**
 * Home before its music has been read: the same page, block for block, with every piece of
 * content replaced by a placeholder of the same size, shape and position. When the real
 * content lands, nothing moves.
 *
 * It is made of the same materials as the page it stands in for. The featured card already
 * carries its strip of real glass, lit by the same light and bending the placeholder
 * beneath it; only the words and the picture are missing.
 */
@Composable
internal fun HomeSkeleton(modifier: Modifier = Modifier) {
    val spacing = MellowTheme.spacing
    val gutter = Modifier.padding(horizontal = spacing.gutter)

    Column(modifier.semantics { contentDescription = "Loading your music" }) {
        FeaturedSkeleton(gutter)

        // "Albums" and its shelf. The row runs off the right edge, as the real one does.
        TitleLine(gutter.padding(top = spacing.xl), width = 96.dp)
        Row(
            modifier = Modifier
                .padding(vertical = HomeDimens.ShelfVerticalPadding)
                .padding(start = spacing.gutter)
                .wrapContentWidth(Alignment.Start, unbounded = true),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            repeat(3) { ShelfAlbumSkeleton() }
        }

        // "Recently added" and its rows
        TitleLine(gutter.padding(top = spacing.sm, bottom = spacing.xs), width = 178.dp)
        repeat(5) { index -> TrackRowSkeleton(index = index) }
    }
}

/**
 * The featured card without its album: the card's own shape and shadow, and over it the
 * real glass strip holding placeholders for the title, the line under it and the button.
 */
@Composable
private fun FeaturedSkeleton(modifier: Modifier = Modifier) {
    val colors = MellowTheme.colors
    val radii = MellowTheme.radii
    val card = remember(radii) { ContinuousCornerShape(radii.card) }

    GlassScene(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(HomeDimens.FeaturedAspect)
            .shadow(22.dp, card.asShadowCaster(), clip = false, ambientColor = colors.shadow, spotColor = colors.shadow),
        frost = MellowTheme.glass.clear.blur,
        backdrop = {
            // Glass needs something solid to look through, as a cover is. The card is
            // therefore a real surface, with the placeholder's light passing over it.
            Box(Modifier.fillMaxSize().background(colors.surface, card)) {
                Skeleton(Modifier.fillMaxSize(), card)
            }
        },
    ) {
        GlassSurface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(HomeDimens.FeaturedStripInset)
                .fillMaxWidth()
                .height(HomeDimens.FeaturedStripHeight),
            shape = GlassShape.Continuous(radii.concentric(radii.card, HomeDimens.FeaturedStripInset)),
            style = MellowTheme.glass.clear,
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(start = 18.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    SkeletonTextLine(width = 138.dp, lineHeight = 22.dp, ink = 14.dp)
                    SkeletonTextLine(width = 112.dp, lineHeight = 18.dp, ink = 11.dp)
                }
                Spacer(Modifier.width(12.dp))
                Skeleton(Modifier.size(HomeDimens.FeaturedPlayButton), CircleShape)
            }
        }
    }
}

/** A placeholder for a section title. */
@Composable
private fun TitleLine(modifier: Modifier, width: Dp) {
    Box(modifier.height(30.dp), contentAlignment = Alignment.CenterStart) {
        Skeleton(Modifier.size(width, 20.dp))
    }
}
