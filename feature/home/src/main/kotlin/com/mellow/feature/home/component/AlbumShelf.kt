package com.mellow.feature.home.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mellow.core.designsystem.component.CoverArt
import com.mellow.core.designsystem.component.Skeleton
import com.mellow.core.designsystem.component.SkeletonTextLine
import com.mellow.core.designsystem.component.Text
import com.mellow.core.designsystem.component.pressable
import com.mellow.core.designsystem.theme.ContinuousCornerShape
import com.mellow.core.designsystem.theme.MellowTheme
import com.mellow.core.model.Album

/**
 * Albums side by side. The row bleeds past the screen edge so it reads as scrollable.
 *
 * @param more true while there are albums on the phone that have not been read yet. One
 *   placeholder then waits at the end of the row.
 * @param onNearEnd called when the row is scrolled close to the last album it has.
 */
@Composable
internal fun AlbumShelf(
    albums: List<Album>,
    more: Boolean,
    onAlbumClick: (String) -> Unit,
    onNearEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MellowTheme.spacing
    val row = rememberLazyListState()

    val nearEnd by remember {
        derivedStateOf {
            val lastVisible = row.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= row.layoutInfo.totalItemsCount - LoadAhead
        }
    }
    LaunchedEffect(nearEnd, albums.size, more) {
        if (nearEnd && more) onNearEnd()
    }

    LazyRow(
        modifier = modifier,
        state = row,
        contentPadding = PaddingValues(horizontal = spacing.gutter, vertical = HomeDimens.ShelfVerticalPadding),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        items(albums, key = { it.id }, contentType = { "album" }) { album ->
            Column(
                modifier = Modifier
                    .width(HomeDimens.ShelfCover)
                    .pressable(onClick = { onAlbumClick(album.id) }, onClickLabel = "Play ${album.title}"),
            ) {
                CoverArt(album.artwork.uri, Modifier.size(HomeDimens.ShelfCover), elevation = 10.dp)
                Spacer(Modifier.height(spacing.sm))
                Text(album.title, style = MellowTheme.type.headline, maxLines = 1)
                Text(album.artist, style = MellowTheme.type.caption, color = MellowTheme.colors.inkMuted, maxLines = 1)
            }
        }
        if (more) {
            item(key = "albums-more", contentType = "album-skeleton") { ShelfAlbumSkeleton() }
        }
    }
}

/** An album on the shelf that has not arrived yet: its cover and its two lines of text. */
@Composable
internal fun ShelfAlbumSkeleton(modifier: Modifier = Modifier) {
    val radii = MellowTheme.radii
    val cover = remember(radii) { ContinuousCornerShape(radii.cover) }
    Column(modifier.width(HomeDimens.ShelfCover)) {
        Skeleton(Modifier.size(HomeDimens.ShelfCover), cover)
        Spacer(Modifier.height(MellowTheme.spacing.sm))
        SkeletonTextLine(width = 104.dp, lineHeight = 22.dp, ink = 14.dp)
        SkeletonTextLine(width = 76.dp, lineHeight = 18.dp, ink = 11.dp)
    }
}

/** How many albums from the end of what is loaded the next page is asked for. */
private const val LoadAhead = 6
