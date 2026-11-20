package com.mellow.feature.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mellow.core.designsystem.component.PullToReload
import com.mellow.core.designsystem.component.SelectionState
import com.mellow.core.designsystem.component.pickedOrNull
import com.mellow.core.designsystem.component.Text
import com.mellow.core.designsystem.component.TrackRow
import com.mellow.core.designsystem.component.TrackRowSkeleton
import com.mellow.core.designsystem.component.formatDuration
import com.mellow.core.designsystem.theme.MellowTheme
import com.mellow.feature.home.component.AccessPrompt
import com.mellow.feature.home.component.AlbumShelf
import com.mellow.feature.home.component.EmptyLibrary
import com.mellow.feature.home.component.FeaturedAlbum
import com.mellow.feature.home.component.HomeHeader
import com.mellow.feature.home.component.HomeSkeleton

/**
 * The main page: what to play next, from the music on this phone.
 *
 * It draws no background of its own. The shell lays it over the lit room, and the floating
 * glass bars sample whatever scrolls beneath them.
 *
 * @param selection the app's one selection. Pressing and holding a song starts it; while
 *   it is on, tapping a song picks or drops it instead of playing it.
 * @param contentPadding room to leave for the status bar above and the floating bars below.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    selection: SelectionState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val reloading by viewModel.reloading.collectAsStateWithLifecycle()
    // Pulling the page down from its top has the phone look again for music: for the song
    // that was just saved by another app and is not showing yet.
    PullToReload(
        reloading = reloading,
        onReload = viewModel::onReload,
        modifier = modifier,
        topInset = contentPadding.calculateTopPadding(),
        // Before access is given there is nothing to look for.
        enabled = state !is HomeUiState.NeedsAccess,
    ) {
        HomeContent(
            state = state,
            selection = selection,
            contentPadding = contentPadding,
            onTrackClick = viewModel::onTrackClick,
            onAlbumClick = viewModel::onAlbumClick,
            onTracksNearEnd = viewModel::onTracksNearEnd,
            onAlbumsNearEnd = viewModel::onAlbumsNearEnd,
            onAccessAnswered = viewModel::onAccessAnswered,
        )
    }
}

@Composable
private fun HomeContent(
    state: HomeUiState,
    selection: SelectionState,
    contentPadding: PaddingValues,
    onTrackClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onTracksNearEnd: () -> Unit,
    onAlbumsNearEnd: () -> Unit,
    onAccessAnswered: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MellowTheme.spacing
    val gutter = Modifier.padding(horizontal = spacing.gutter)
    val list = rememberLazyListState()

    // Ask for the next page well before the last row is on screen, so in ordinary
    // scrolling it has arrived by the time the user gets there.
    val ready = state as? HomeUiState.Ready
    val nearEnd by remember {
        derivedStateOf {
            val lastVisible = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= list.layoutInfo.totalItemsCount - LoadAhead
        }
    }
    LaunchedEffect(nearEnd, ready?.tracks?.size, ready?.moreTracks) {
        if (nearEnd && ready?.moreTracks == true) onTracksNearEnd()
    }

    LazyColumn(modifier = modifier.fillMaxSize(), state = list, contentPadding = contentPadding) {
        item(key = "header") {
            HomeHeader(gutter.padding(top = spacing.lg, bottom = spacing.lg))
        }

        when (state) {
            HomeUiState.Loading -> item(key = "loading") { HomeSkeleton() }

            HomeUiState.NeedsAccess -> item(key = "access") { AccessPrompt(onAccessAnswered, gutter) }

            HomeUiState.Empty -> item(key = "empty") { EmptyLibrary(gutter) }

            is HomeUiState.Ready -> {
                item(key = "featured") {
                    FeaturedAlbum(
                        album = state.featured,
                        playing = state.isPlaying(state.featured),
                        onClick = { onAlbumClick(state.featured.id) },
                        modifier = gutter,
                    )
                }
                item(key = "albums") {
                    Column {
                        SectionTitle("Albums", gutter.padding(top = spacing.xl))
                        AlbumShelf(state.albums, state.moreAlbums, onAlbumClick, onAlbumsNearEnd)
                    }
                }
                item(key = "tracks-title") {
                    SectionTitle("Recently added", gutter.padding(top = spacing.sm, bottom = spacing.xs))
                }
                items(state.tracks, key = { it.id }, contentType = { "track" }) { track ->
                    TrackRow(
                        title = track.title,
                        subtitle = track.artist,
                        artworkUri = track.artwork.uri,
                        onClick = { if (selection.active) selection.toggle(track.id) else onTrackClick(track.id) },
                        trailing = formatDuration(track.durationMs),
                        current = track.id == state.currentTrackId,
                        playing = state.isPlaying,
                        selected = selection.pickedOrNull(track.id),
                        onLongClick = { selection.toggle(track.id) },
                    )
                }
                // The rows still to come, as placeholders of themselves. Only seen when
                // the scroll outruns the next page.
                if (state.moreTracks) {
                    items(TailSkeletons, key = { "tracks-more-$it" }, contentType = { "track-skeleton" }) { index ->
                        TrackRowSkeleton(index = index)
                    }
                }
                item(key = "end") { Spacer(Modifier.height(spacing.md)) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MellowTheme.type.title, modifier = modifier.semantics { heading() })
}

/** How many rows from the end of what is loaded the next page is asked for. */
private const val LoadAhead = 24
private const val TailSkeletons = 3
