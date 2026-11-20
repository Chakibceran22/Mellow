package com.mellow.feature.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mellow.core.designsystem.component.SelectionState
import com.mellow.core.designsystem.theme.MellowMotion
import com.mellow.feature.library.component.PlaylistList
import com.mellow.feature.library.component.PlaylistPage

/**
 * The Library tab: the user's playlists, and whichever one of them is open.
 *
 * Opening a playlist does not leave the tab. Its page slides in over the list, and the
 * arrow on the page slides it away again. So does the system back button, which the shell
 * routes here through [LibraryViewModel.closePlaylist].
 *
 * Like every page it draws no background of its own. The sheets it asks questions in are
 * not in here either: see [LibrarySheets], which the shell floats over the whole app.
 *
 * @param selection the app's one selection. Songs in a playlist are picked into it.
 * @param contentPadding room to leave for the status bar above and the floating bars below.
 * @param onOpenSettings the gear at the top of the list was pressed.
 */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    selection: SelectionState,
    contentPadding: PaddingValues,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // The page that is sliding away still has to show the playlist it was showing.
    val lastOpen = remember { Kept<OpenPlaylist>() }
    state.open?.let { lastOpen.value = it }

    AnimatedContent(
        targetState = state.open != null,
        modifier = modifier.fillMaxSize(),
        transitionSpec = {
            // A playlist comes in from the right and leaves the way it came.
            val direction = if (targetState) 1 else -1
            (fadeIn(MellowMotion.glide()) + slideInHorizontally(MellowMotion.glide()) { direction * it / 6 }) togetherWith
                (fadeOut(MellowMotion.glide()) + slideOutHorizontally(MellowMotion.glide()) { -direction * it / 6 })
        },
        label = "library",
    ) { showingPlaylist ->
        val open = state.open ?: lastOpen.value
        if (showingPlaylist && open != null) {
            PlaylistPage(
                open = open,
                shuffle = state.shuffle,
                currentTrackId = state.currentTrackId,
                isPlaying = state.isPlaying,
                selection = selection,
                contentPadding = contentPadding,
                onBack = viewModel::closePlaylist,
                onChangeCover = viewModel::askToChangeCoverOfOpen,
                onRename = viewModel::askToRenameOpen,
                onDelete = viewModel::askToDeleteOpen,
                onPlay = viewModel::onPlayClick,
                onShuffleToggle = viewModel::onShuffleToggle,
                onTrackClick = viewModel::onTrackClick,
                onNearEnd = viewModel::onPlaylistNearEnd,
            )
        } else {
            PlaylistList(
                playlists = state.playlists,
                contentPadding = contentPadding,
                onOpen = viewModel::openPlaylist,
                onCreate = { viewModel.askToCreate() },
                onOpenSettings = onOpenSettings,
            )
        }
    }
}

/** Holds on to the last value it was given. Not state: reading it never causes a redraw. */
internal class Kept<T : Any> {
    var value: T? = null
}
