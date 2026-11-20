package com.mellow.feature.library.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mellow.core.designsystem.component.CoverMosaic
import com.mellow.core.designsystem.component.Text
import com.mellow.core.designsystem.component.pressable
import com.mellow.core.designsystem.theme.MellowTheme
import com.mellow.core.model.Playlist
import com.mellow.feature.library.songs

/** One playlist in the Library: its cover, its name, how much is in it. */
@Composable
internal fun PlaylistRow(
    playlist: Playlist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val covers = remember(playlist.covers) { playlist.covers.map { it.uri } }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .pressable(onClick = onClick, onClickLabel = "Open ${playlist.name}", pressedScale = 0.98f)
            .padding(horizontal = MellowTheme.spacing.gutter, vertical = LibraryDimens.RowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverMosaic(covers, Modifier.size(LibraryDimens.RowCover), radius = LibraryDimens.RowCoverRadius, elevation = 4.dp)
        Spacer(Modifier.width(LibraryDimens.RowGap))
        Column(Modifier.weight(1f)) {
            Text(playlist.name, style = MellowTheme.type.headline, maxLines = 1)
            Text(songs(playlist.trackCount), style = MellowTheme.type.caption, color = MellowTheme.colors.inkMuted, maxLines = 1)
        }
    }
}
