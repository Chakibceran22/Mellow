package com.mellow.feature.library.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mellow.core.designsystem.component.CoverMosaic
import com.mellow.core.designsystem.component.GlassIconButton
import com.mellow.core.designsystem.component.Icon
import com.mellow.core.designsystem.component.SelectionState
import com.mellow.core.designsystem.component.pickedOrNull
import com.mellow.core.designsystem.component.Text
import com.mellow.core.designsystem.component.TrackRow
import com.mellow.core.designsystem.component.TrackRowSkeleton
import com.mellow.core.designsystem.component.formatDuration
import com.mellow.core.designsystem.component.pressable
import com.mellow.core.designsystem.glass.GlassShape
import com.mellow.core.designsystem.glass.GlassSurface
import com.mellow.core.designsystem.icon.MellowIcons
import com.mellow.core.designsystem.theme.MellowTheme
import com.mellow.feature.library.OpenPlaylist
import com.mellow.feature.library.songs

/**
 * One playlist, opened: its cover and name, the two controls that play it, and its songs.
 *
 * The controls work as a pair. Shuffle is a switch, not a command: it only says how the
 * playlist should be played. The round button does the playing, in order or shuffled
 * according to the switch.
 *
 * Songs are selected the same way as everywhere else, by pressing and holding one. The
 * cover is changed by tapping it; it is its own control, and has no button of its own.
 *
 * The list holds only as much of the playlist as has been scrolled to. Nearing the end of
 * that calls [onNearEnd] for more, and a few placeholder rows stand in if the scroll gets
 * there first.
 *
 * @param shuffle whether the player is set to shuffle.
 * @param currentTrackId the track loaded in the player, playing or paused.
 */
@Composable
internal fun PlaylistPage(
    open: OpenPlaylist,
    shuffle: Boolean,
    currentTrackId: String?,
    isPlaying: Boolean,
    selection: SelectionState,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onChangeCover: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onPlay: () -> Unit,
    onShuffleToggle: () -> Unit,
    onTrackClick: (String) -> Unit,
    onNearEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MellowTheme.spacing
    val colors = MellowTheme.colors
    val gutter = Modifier.padding(horizontal = spacing.gutter)
    val playlist = open.playlist
    val tracks = open.tracks
    val covers = remember(playlist.covers) { playlist.covers.map { it.uri } }
    val list = rememberLazyListState()

    // Ask for the next page well before the last row is on screen.
    val nearEnd by remember {
        derivedStateOf {
            val lastVisible = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= list.layoutInfo.totalItemsCount - LoadAhead
        }
    }
    LaunchedEffect(nearEnd, tracks?.size, open.moreTracks) {
        if (nearEnd && open.moreTracks) onNearEnd()
    }

    LazyColumn(modifier = modifier.fillMaxSize(), state = list, contentPadding = contentPadding) {
        item(key = "bar") {
            Row(gutter.fillMaxWidth().padding(top = spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(MellowIcons.Back, contentDescription = "Back to Library", onClick = onBack)
                Spacer(Modifier.weight(1f))
                GlassIconButton(MellowIcons.Rename, contentDescription = "Rename playlist", onClick = onRename)
                Spacer(Modifier.width(10.dp))
                GlassIconButton(MellowIcons.Delete, contentDescription = "Delete playlist", onClick = onDelete)
            }
        }

        item(key = "hero") {
            Column(
                modifier = gutter.fillMaxWidth().padding(top = spacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CoverMosaic(
                    uris = covers,
                    modifier = Modifier
                        .size(LibraryDimens.HeroCover)
                        .pressable(onClick = onChangeCover, onClickLabel = "Change cover", pressedScale = 0.97f),
                    radius = MellowTheme.radii.card,
                    elevation = 18.dp,
                )
                Spacer(Modifier.height(spacing.lg))
                Text(
                    text = playlist.name,
                    style = MellowTheme.type.title,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(2.dp))
                Text(songs(playlist.trackCount), style = MellowTheme.type.callout, color = colors.inkMuted)
            }
        }

        item(key = "controls") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = spacing.lg, bottom = spacing.md),
                horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShuffleSwitch(on = shuffle, onToggle = onShuffleToggle)
                PlayButton(playing = open.playing, onClick = onPlay)
            }
        }

        when {
            tracks == null -> items(4, key = { "loading-$it" }) { TrackRowSkeleton(index = it) }

            tracks.isEmpty() -> item(key = "empty") {
                Column(gutter.padding(top = spacing.xs)) {
                    Text("Nothing in here yet", style = MellowTheme.type.headline)
                    Spacer(Modifier.height(spacing.xxs))
                    Text(
                        text = "Press and hold a song on Home or in Search, pick the ones you want, " +
                            "then choose Add to playlist.",
                        color = colors.inkMuted,
                        modifier = Modifier.widthIn(max = 340.dp),
                    )
                }
            }

            else -> items(tracks, key = { it.id }, contentType = { "track" }) { track ->
                TrackRow(
                    title = track.title,
                    subtitle = track.artist,
                    artworkUri = track.artwork.uri,
                    onClick = { if (selection.active) selection.toggle(track.id) else onTrackClick(track.id) },
                    trailing = formatDuration(track.durationMs),
                    current = track.id == currentTrackId,
                    playing = isPlaying,
                    selected = selection.pickedOrNull(track.id),
                    onLongClick = { selection.toggle(track.id) },
                )
            }
        }
        // The rows still to come, as placeholders of themselves.
        if (!tracks.isNullOrEmpty() && open.moreTracks) {
            items(TailSkeletons, key = { "more-$it" }, contentType = { "track-skeleton" }) { TrackRowSkeleton(index = it) }
        }
    }
}

/**
 * Shuffle, as a switch. Plain glass while off; it fills with green when on, so its state
 * can be read without reading the word.
 */
@Composable
private fun ShuffleSwitch(on: Boolean, onToggle: () -> Unit) {
    val colors = MellowTheme.colors
    val glass = MellowTheme.glass
    GlassSurface(
        modifier = Modifier
            .height(LibraryDimens.ShuffleHeight)
            .semantics { stateDescription = if (on) "On" else "Off" },
        shape = GlassShape.Capsule,
        style = if (on) glass.accent else glass.regular,
        onClick = onToggle,
        onClickLabel = if (on) "Turn shuffle off" else "Turn shuffle on",
        role = Role.Switch,
        pressScale = 1.05f,
        contentColor = if (on) colors.onAccent else colors.ink,
    ) {
        Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(MellowIcons.Shuffle, contentDescription = null, size = 20.dp)
            Spacer(Modifier.width(10.dp))
            Text("Shuffle", style = MellowTheme.type.headline)
        }
    }
}

/** The one green disc on the page: plays the playlist, or pauses it when it is what is on. */
@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    GlassSurface(
        modifier = Modifier.size(LibraryDimens.PlayButton),
        shape = GlassShape.Capsule,
        style = MellowTheme.glass.accent,
        onClick = onClick,
        role = Role.Button,
        pressScale = 1.08f,
        contentColor = MellowTheme.colors.onAccent,
    ) {
        Icon(
            icon = if (playing) MellowIcons.Pause else MellowIcons.Play,
            contentDescription = if (playing) "Pause" else "Play playlist",
            size = 28.dp,
        )
    }
}

/** How many rows from the end of what is loaded the next page is asked for. */
private const val LoadAhead = 16
private const val TailSkeletons = 3
