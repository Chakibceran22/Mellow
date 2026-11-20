package com.mellow.feature.search.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mellow.core.designsystem.component.CoverArt
import com.mellow.core.designsystem.component.Text
import com.mellow.core.designsystem.component.pressable
import com.mellow.core.designsystem.theme.MellowTheme
import com.mellow.core.model.Album

/**
 * The albums a search found, as covers side by side. Songs are rows and albums are covers
 * everywhere in the app, so the two kinds of result can be told apart before a word is
 * read. The row bleeds past the screen edge so it reads as scrollable.
 *
 * @param currentAlbumId the album the loaded track belongs to; it takes the accent colour.
 * @param playing whether sound is coming out right now.
 */
@Composable
internal fun AlbumResults(
    albums: List<Album>,
    currentAlbumId: String?,
    playing: Boolean,
    onAlbumClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MellowTheme.colors
    val spacing = MellowTheme.spacing
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = spacing.gutter, vertical = SearchDimens.ShelfVerticalPadding),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        items(albums, key = { it.id }) { album ->
            val current = album.id == currentAlbumId
            Column(
                modifier = Modifier
                    .width(SearchDimens.AlbumCover)
                    .pressable(
                        onClick = { onAlbumClick(album.id) },
                        onClickLabel = if (current && playing) "Pause ${album.title}" else "Play ${album.title}",
                    ),
            ) {
                CoverArt(album.artwork.uri, Modifier.size(SearchDimens.AlbumCover), elevation = 8.dp)
                Spacer(Modifier.height(spacing.sm))
                Text(
                    text = album.title,
                    style = MellowTheme.type.headline,
                    color = if (current) colors.accent else colors.ink,
                    maxLines = 1,
                )
                Text(album.artist, style = MellowTheme.type.caption, color = colors.inkMuted, maxLines = 1)
                Text(trackCount(album.trackCount), style = MellowTheme.type.caption, color = colors.inkFaint, maxLines = 1)
            }
        }
    }
}

private fun trackCount(count: Int): String = if (count == 1) "1 track" else "$count tracks"
