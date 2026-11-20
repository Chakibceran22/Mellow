package com.mellow.feature.library.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mellow.core.designsystem.component.Button
import com.mellow.core.designsystem.component.GlassIconButton
import com.mellow.core.designsystem.component.Text
import com.mellow.core.designsystem.component.TrackRowSkeleton
import com.mellow.core.designsystem.icon.MellowIcons
import com.mellow.core.designsystem.theme.MellowTheme
import com.mellow.core.model.Playlist

/**
 * The Library's front page: the playlists the user has made, newest work first, and the
 * way to make another.
 *
 * @param playlists null while they are being read.
 */
@Composable
internal fun PlaylistList(
    playlists: List<Playlist>?,
    contentPadding: PaddingValues,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MellowTheme.spacing
    val gutter = Modifier.padding(horizontal = spacing.gutter)

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(key = "header") {
            Row(
                modifier = gutter.fillMaxWidth().padding(top = spacing.lg, bottom = spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Library",
                    style = MellowTheme.type.display,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                // Out of the way of everyday use, but always in the same place.
                GlassIconButton(MellowIcons.Settings, contentDescription = "Open settings", onClick = onOpenSettings)
            }
        }
        item(key = "playlists-title") {
            // The row is as tall as the button whether or not the button is there, so the
            // list under it does not move when the first playlist is made.
            Row(gutter.fillMaxWidth().heightIn(min = 46.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Playlists",
                    style = MellowTheme.type.title,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                // With no playlists the page has a full button for this instead.
                if (!playlists.isNullOrEmpty()) {
                    GlassIconButton(MellowIcons.Add, contentDescription = "Create playlist", onClick = onCreate)
                }
            }
        }
        when {
            playlists == null -> items(3, key = { "loading-$it" }) { TrackRowSkeleton(index = it) }

            playlists.isEmpty() -> item(key = "empty") {
                Column(gutter.padding(top = spacing.xs)) {
                    Text(
                        text = "A playlist is a list of songs you put together. Press and hold any song to pick it, " +
                            "pick as many as you like, then choose Add to playlist.",
                        color = MellowTheme.colors.inkMuted,
                        modifier = Modifier.widthIn(max = 340.dp),
                    )
                    Spacer(Modifier.height(spacing.lg))
                    Button("Create playlist", onClick = onCreate, icon = MellowIcons.Add)
                }
            }

            else -> items(playlists, key = { it.id }) { playlist ->
                PlaylistRow(playlist, onClick = { onOpen(playlist.id) })
            }
        }
    }
}
