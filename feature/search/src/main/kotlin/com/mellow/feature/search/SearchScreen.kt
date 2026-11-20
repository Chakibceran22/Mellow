package com.mellow.feature.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mellow.core.designsystem.component.SelectionState
import com.mellow.core.designsystem.component.pickedOrNull
import com.mellow.core.designsystem.component.Text
import com.mellow.core.designsystem.component.TrackRow
import com.mellow.core.designsystem.component.formatDuration
import com.mellow.core.designsystem.theme.MellowTheme
import com.mellow.feature.search.component.AlbumResults
import com.mellow.feature.search.component.SearchMessage
import com.mellow.feature.search.component.SearchSkeleton

/**
 * The Search page: whatever the library holds that matches what is typed in [SearchBar].
 *
 * Like every page it is plain content with no background of its own, and it is part of the
 * backdrop the app's glass looks through. That is why the bar is not in here: see
 * [SearchBar]. The shell floats the bar over this page; results scroll up underneath it.
 *
 * @param selection the app's one selection. Pressing and holding a song that was found
 *   starts it; while it is on, tapping a song picks or drops it instead of playing it.
 * @param contentPadding room to leave above and below. The top already allows for the
 *   status bar and the search bar; the bottom for the floating bars.
 */
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    selection: SelectionState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current

    // Dragging the results puts the keyboard away: it is covering what was just found.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) focusManager.clearFocus()
        }
    }

    // A different query is a different list; start it from the top.
    val answered = (state as? SearchUiState.Results)?.query
    LaunchedEffect(answered) { listState.scrollToItem(0) }

    SearchContent(
        state = state,
        selection = selection,
        listState = listState,
        contentPadding = contentPadding,
        onTrackClick = viewModel::onTrackClick,
        onAlbumClick = viewModel::onAlbumClick,
        modifier = modifier,
    )
}

@Composable
private fun SearchContent(
    state: SearchUiState,
    selection: SelectionState,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onTrackClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MellowTheme.spacing
    val gutter = Modifier.padding(horizontal = spacing.gutter)

    // The keyboard covers the floating bars and more, so while it is up the list ends
    // above the keyboard instead of above them.
    val keyboard = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val padding = PaddingValues(
        top = contentPadding.calculateTopPadding(),
        bottom = max(contentPadding.calculateBottomPadding(), keyboard + spacing.md),
    )

    LazyColumn(modifier = modifier.fillMaxSize(), state = listState, contentPadding = padding) {
        when (state) {
            SearchUiState.Idle -> item(key = "idle") {
                SearchMessage(
                    title = "Search",
                    message = "Find songs, albums and artists among the music on this phone.",
                )
            }

            SearchUiState.NoAccess -> item(key = "no-access") {
                SearchMessage(
                    title = "Search",
                    message = "Mellow has not been allowed to read the music on this phone yet. " +
                        "Allow it from Home, then search here.",
                )
            }

            SearchUiState.Searching -> item(key = "searching") { SearchSkeleton() }

            is SearchUiState.NothingFound -> item(key = "nothing") {
                SearchMessage(
                    title = "Nothing for “${state.query}”",
                    message = "No song, album or artist on this phone has that in its name. " +
                        "Check the spelling, or try fewer letters.",
                    titleStyle = MellowTheme.type.title,
                )
            }

            is SearchUiState.Results -> {
                val hasAlbums = state.albums.isNotEmpty()
                if (hasAlbums) {
                    item(key = "albums") {
                        Column {
                            SectionTitle("Albums", gutter)
                            AlbumResults(
                                albums = state.albums,
                                currentAlbumId = state.currentAlbumId,
                                playing = state.isPlaying,
                                onAlbumClick = onAlbumClick,
                            )
                        }
                    }
                }
                if (state.tracks.isNotEmpty()) {
                    item(key = "songs-title") {
                        SectionTitle(
                            text = "Songs",
                            modifier = gutter.padding(top = if (hasAlbums) spacing.sm else 0.dp, bottom = spacing.xs),
                        )
                    }
                    items(state.tracks, key = { it.id }) { track ->
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
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MellowTheme.type.title, modifier = modifier.semantics { heading() })
}
