package com.mellow.feature.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mellow.core.designsystem.component.Button
import com.mellow.core.designsystem.component.ButtonKind
import com.mellow.core.designsystem.component.CoverArt
import com.mellow.core.designsystem.component.CoverMosaic
import com.mellow.core.designsystem.component.GlassSheet
import com.mellow.core.designsystem.component.Icon
import com.mellow.core.designsystem.component.SelectionMark
import com.mellow.core.designsystem.component.Text
import com.mellow.core.designsystem.component.TextField
import com.mellow.core.designsystem.component.pressable
import com.mellow.core.designsystem.icon.MellowIcons
import com.mellow.core.designsystem.theme.ContinuousCornerShape
import com.mellow.core.designsystem.theme.MellowTheme
import com.mellow.core.model.Playlist
import com.mellow.core.model.PlaylistCover
import com.mellow.core.model.Track
import com.mellow.feature.library.component.LibraryDimens

/**
 * The questions the Library asks, each in a sheet of glass over the whole app: which
 * playlist to add to, what to call one, what its cover should be, whether to really delete
 * one, and what to change about the songs that are selected.
 *
 * It is separate from [LibraryScreen] because a sheet has to cover everything, the tab bar
 * included, and because it is asked for from every page (songs are added to playlists from
 * Home and Search as much as from here). Call it last in the content layer of the app's
 * `GlassScene`, so it is drawn over the rest. The system back button is the shell's to
 * route: it should call [LibraryViewModel.dismissSheet] while [LibraryViewModel.sheet] is set.
 *
 * @param onDeleteFromPhone the user chose to delete these songs' files. Android has to be
 *   asked about that by an activity, so it is the shell's to do.
 */
@Composable
fun BoxScope.LibrarySheets(viewModel: LibraryViewModel, onDeleteFromPhone: (Set<String>) -> Unit = {}) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sheet = viewModel.sheet

    // The sheet keeps showing its question while it slides away.
    val last = remember { Kept<LibrarySheet>() }
    if (sheet != null) last.value = sheet

    // How many times a sheet has come up. What was chosen on one sheet is forgotten when
    // the next one opens, not when this one closes: it is still on screen while it leaves.
    val openings = remember { Openings() }
    if (sheet != null && sheet != openings.showing) openings.count++
    openings.showing = sheet

    // The system's photo picker, for a playlist cover. It needs no permission: the user
    // hands over the one picture they pick and nothing else.
    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked ->
        if (picked != null) viewModel.usePictureCover(picked.toString())
    }

    // The same picker, for the cover of one song or several. For one song the picture is
    // only held until its edit is saved; for several it is the whole edit, so it is applied.
    var pickedForSong by remember(openings.count) { mutableStateOf<String?>(null) }
    val pickSongPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked ->
        if (picked != null) {
            if (viewModel.editing?.single != null) pickedForSong = picked.toString() else viewModel.useCoverForEdited(picked.toString())
        }
    }
    val choosePicture = { pickSongPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    // Closing a sheet with the keyboard up takes the keyboard with it.
    val focusManager = LocalFocusManager.current
    LaunchedEffect(sheet == null) {
        if (sheet == null) focusManager.clearFocus()
    }

    GlassSheet(visible = sheet != null, onDismiss = viewModel::dismissSheet) {
        when (val shown = sheet ?: last.value) {
            is LibrarySheet.AddTo -> AddToPlaylist(
                trackCount = shown.trackIds.size,
                playlists = state.playlists.orEmpty(),
                onPick = { viewModel.addTo(it, shown.trackIds) },
                onNew = { viewModel.askToCreate(shown.trackIds) },
            )

            is LibrarySheet.Name -> NamePlaylist(asked = shown, onSave = viewModel::saveName)

            is LibrarySheet.ConfirmDelete -> ConfirmDelete(
                playlist = shown.playlist,
                onDelete = viewModel::deleteConfirmed,
                onKeep = viewModel::dismissSheet,
            )

            is LibrarySheet.Cover -> ChooseCover(
                current = state.playlists.orEmpty().firstOrNull { it.id == shown.playlistId }?.cover ?: PlaylistCover.Automatic,
                tracks = state.open?.takeIf { it.playlist.id == shown.playlistId }?.tracks.orEmpty(),
                onAutomatic = viewModel::useAutomaticCover,
                onPicture = {
                    pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onTrack = viewModel::useTrackCover,
            )

            is LibrarySheet.More -> MoreActions(
                count = shown.trackIds.size,
                inPlaylist = shown.inPlaylist,
                canDelete = viewModel.canDeleteFiles,
                onEdit = { viewModel.askToEdit(shown.trackIds) },
                onRemove = { viewModel.removeFromOpen(shown.trackIds) },
                onDelete = {
                    viewModel.dismissSheet()
                    onDeleteFromPhone(shown.trackIds)
                },
            )

            is LibrarySheet.MovePictures -> MovePictures(
                count = shown.count,
                unfinished = shown.unfinished,
                onMove = viewModel::moveWaitingPictures,
                onLater = viewModel::dismissSheet,
            )

            is LibrarySheet.EditSongs -> {
                val editing = viewModel.editing
                val song = editing?.single
                when {
                    // Not expected: the songs are read before the sheet is shown.
                    editing == null -> SheetTitle(if (shown.trackIds.size == 1) "Edit song" else "Edit ${songs(shown.trackIds.size)}")
                    song != null -> EditSong(
                        editing = editing,
                        song = song,
                        writesToSong = viewModel.writesToSongs,
                        pickedPicture = pickedForSong,
                        onChoosePicture = choosePicture,
                        onSave = viewModel::saveSongEdit,
                        onUndo = viewModel::undoEditsOfEdited,
                    )
                    else -> EditSongsTogether(
                        editing = editing,
                        writesToSongs = viewModel.writesToSongs,
                        onChoosePicture = choosePicture,
                        onUseCoverOf = viewModel::useCoverOfEdited,
                        onUseOwnCovers = viewModel::useOwnCoversForEdited,
                    )
                }
            }

            null -> Unit
        }
    }
}

/** Counts the sheets that have come up. Not state: it is only ever read where it is written. */
private class Openings {
    var showing: LibrarySheet? = null
    var count = 0
}

/**
 * One song: what it is called, who it is by, which album it says it is from, and its
 * cover.
 *
 * What is changed here is written into the song's own file, names and cover alike, so it
 * becomes true of the song everywhere and not only in Mellow. The sheet says so above its
 * button, because that is a bigger thing to agree to than a change inside one app.
 *
 * Nothing changes until Save is pressed, the picture included.
 *
 * The cover is its own control: a tap on it chooses another picture. There is no button
 * beside it saying so.
 *
 * @param writesToSong false on a phone where Mellow can only keep changes to itself.
 */
@Composable
private fun EditSong(
    editing: EditingSongs,
    song: Track,
    writesToSong: Boolean,
    pickedPicture: String?,
    onChoosePicture: () -> Unit,
    onSave: (title: String, artist: String, album: String, picture: PictureChange, wholeAlbum: Boolean) -> Unit,
    onUndo: () -> Unit,
) {
    val colors = MellowTheme.colors
    val focusManager = LocalFocusManager.current
    val filed = editing.asFiled(song)
    var title by remember(song.id) { mutableStateOf(TextFieldValue(song.title)) }
    var artist by remember(song.id) { mutableStateOf(TextFieldValue(song.artist)) }
    var album by remember(song.id) { mutableStateOf(TextFieldValue(song.album)) }
    var backToOwnCover by remember(song.id) { mutableStateOf(false) }
    var wholeAlbum by remember(song.id) { mutableStateOf(false) }

    val wearsOwnPicture = song.id in editing.pictureFromMellow
    val picture = when {
        pickedPicture != null -> PictureChange.Use(pickedPicture)
        backToOwnCover -> PictureChange.UseItsOwn
        else -> PictureChange.Keep
    }
    // What the cover will be once saved. A cover Mellow only keeps to itself hides the
    // song's own, which can be shown. One written into the file replaced it there, and
    // the original is put aside where there is no picture to show of it until it is back.
    val preview = when (picture) {
        is PictureChange.Use -> picture.pictureUri
        PictureChange.UseItsOwn -> filed?.artwork?.uri?.takeIf { it != song.artwork.uri }
        PictureChange.Keep -> song.artwork.uri
    }

    SheetTitle("Edit song")
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        CoverArt(
            uri = preview,
            modifier = Modifier
                .size(EditCover)
                .pressable(onClick = onChoosePicture, onClickLabel = "Choose a picture", pressedScale = 0.95f),
            shape = remember { ContinuousCornerShape(EditCoverRadius) },
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("From the album", style = MellowTheme.type.caption, color = colors.inkMuted)
            Text(filed?.album ?: song.album, style = MellowTheme.type.headline, maxLines = 2)
            if (wearsOwnPicture && picture is PictureChange.Keep) {
                Spacer(Modifier.height(2.dp))
                TextLink("Go back to its original picture", onClick = { backToOwnCover = true })
            }
        }
    }
    if (picture is PictureChange.UseItsOwn) {
        Spacer(Modifier.height(10.dp))
        Text("The picture it came with will be put back when you save.", style = MellowTheme.type.caption, color = colors.inkMuted)
    }

    Spacer(Modifier.height(16.dp))
    FieldLabel("Title")
    TextField(title, { title = it }, label = "Title", onDone = { focusManager.clearFocus() })
    Spacer(Modifier.height(12.dp))
    FieldLabel("Artist")
    TextField(artist, { artist = it }, label = "Artist", onDone = { focusManager.clearFocus() })
    Spacer(Modifier.height(12.dp))
    FieldLabel("Album")
    TextField(album, { album = it }, label = "Album", onDone = { focusManager.clearFocus() })

    // Offered only when it would do something: a new picture, and other songs to share it with.
    if (picture is PictureChange.Use && editing.albumMates > 0) {
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressable(onClick = { wholeAlbum = !wholeAlbum }, role = Role.Checkbox, pressedScale = 0.98f)
                .semantics { selected = wholeAlbum }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectionMark(wholeAlbum)
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Also give this picture to the ${if (editing.albumMates == 1) "other song" else "${editing.albumMates} other songs"} " +
                    "from ${filed?.album ?: song.album}",
                style = MellowTheme.type.callout,
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (writesToSong) {
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Saved into the song itself, so every app shows it. Android will ask you to allow it.",
            style = MellowTheme.type.caption,
            color = colors.inkMuted,
        )
    }
    Spacer(Modifier.height(if (writesToSong) 12.dp else 18.dp))
    Button(
        text = "Save changes",
        onClick = { onSave(title.text, artist.text, album.text, picture, wholeAlbum) },
        modifier = Modifier.fillMaxWidth(),
    )
    // Only when there is something to undo: the song already differs from its file.
    if (editing.anyEdited) {
        Spacer(Modifier.height(10.dp))
        Button("Undo every change to this song", onClick = onUndo, kind = ButtonKind.Quiet, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Several songs at once: the one thing that can be shared between them is a cover. It
 * says which albums they come from, since that is usually why they need one.
 *
 * The cover can be a picture from the phone, or the one that one of them already wears:
 * a tap on it gives it to the rest. A song with no picture has nothing to give, and its
 * tile does nothing.
 */
@Composable
private fun EditSongsTogether(
    editing: EditingSongs,
    writesToSongs: Boolean,
    onChoosePicture: () -> Unit,
    onUseCoverOf: (trackId: String) -> Unit,
    onUseOwnCovers: () -> Unit,
) {
    val colors = MellowTheme.colors
    val count = editing.tracks.size
    val albums = editing.albums

    SheetTitle("Edit ${songs(count)}")
    Spacer(Modifier.height(6.dp))
    Text(
        text = when (albums.size) {
            1 -> "All from the album ${albums[0]}."
            2 -> "From 2 albums: ${albums[0]} and ${albums[1]}."
            else -> "From ${albums.size} albums, among them ${albums[0]} and ${albums[1]}."
        },
        color = colors.inkMuted,
    )

    // Their covers as they are now, each once. With only one there is nothing to choose between.
    val covers = remember(editing) { editing.tracks.distinctBy { it.artwork.uri } }
    var noCover by remember(editing) { mutableStateOf(emptySet<String>()) }
    val canShare = covers.size > 1 && covers.any { it.id !in noCover }
    Spacer(Modifier.height(14.dp))
    if (canShare) {
        Text("Tap a cover to give it to all $count", style = MellowTheme.type.callout, color = colors.inkMuted)
        Spacer(Modifier.height(10.dp))
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(covers, key = { it.id }) { track ->
            val shareable = canShare && track.id !in noCover
            CoverArt(
                uri = track.artwork.uri,
                modifier = Modifier
                    .size(SongCover)
                    .then(
                        if (shareable) {
                            Modifier.pressable(
                                onClick = { onUseCoverOf(track.id) },
                                onClickLabel = "Give the cover of ${track.title} to all $count",
                                pressedScale = 0.94f,
                            )
                        } else {
                            Modifier
                        },
                    ),
                shape = remember { ContinuousCornerShape(SongCoverRadius) },
                onNoCover = { noCover = noCover + track.id },
            )
        }
    }

    Spacer(Modifier.height(14.dp))
    Text(
        text = if (writesToSongs) {
            "A picture you choose is written into all $count songs, so every app shows it. To change a name, edit one song by itself."
        } else {
            "A picture you choose becomes the cover of all $count. To change a name, edit one song by itself."
        },
        color = colors.inkMuted,
    )
    Spacer(Modifier.height(18.dp))
    Button("Choose a picture for all $count", onClick = onChoosePicture, icon = MellowIcons.Picture, modifier = Modifier.fillMaxWidth())
    if (editing.anyOwnPicture) {
        Spacer(Modifier.height(10.dp))
        Button("Go back to their original pictures", onClick = onUseOwnCovers, kind = ButtonKind.Quiet, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Covers chosen when Mellow kept them to itself, offered a place in their songs. It says
 * what will happen to the files before Android asks, because Android's own question only
 * says that they will be modified.
 */
@Composable
private fun MovePictures(count: Int, unfinished: Int, onMove: () -> Unit, onLater: () -> Unit) {
    val one = count == 1
    // Mellow was closed in the middle of writing to a song. That comes first and is said
    // plainly, because until it is finished that song may not be whole.
    val interrupted = unfinished > 0
    SheetTitle(
        when {
            interrupted -> if (one) "Finish saving a song" else "Finish saving your songs"
            one -> "Put your changes into the song"
            else -> "Put your changes into the songs"
        },
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = if (interrupted) {
            (if (unfinished == 1) "Mellow was closed while it was writing to a song. " else "Mellow was closed while it was writing to $unfinished songs. ") +
                "It kept what it needs to finish the job, or to put the song back exactly as it was. Until then the song may not play right." +
                if (count > unfinished) " ${count - unfinished} more ${if (count - unfinished == 1) "song has" else "songs have"} changes waiting to be written as well." else ""
        } else {
            (if (one) "1 song has a name or a cover you changed that only Mellow can see. " else "$count songs have a name or a cover you changed that only Mellow can see. ") +
                "Written into the song itself, it becomes true of the song on this phone and in every app. " +
                "Nothing else about the song is changed, and what it said before is kept so you can go back."
        },
        color = MellowTheme.colors.inkMuted,
    )
    Spacer(Modifier.height(10.dp))
    Text("Android will ask you to allow it.", color = MellowTheme.colors.inkMuted)
    Spacer(Modifier.height(18.dp))
    Button(
        text = when {
            interrupted -> "Finish now"
            one -> "Write them into the song"
            else -> "Write them into the songs"
        },
        onClick = onMove,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    Button("Not now", onClick = onLater, kind = ButtonKind.Quiet, modifier = Modifier.fillMaxWidth())
}

/** The name of a field, above it. Always visible, so a filled-in field still says what it is. */
@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MellowTheme.type.caption, color = MellowTheme.colors.inkMuted, modifier = Modifier.padding(start = 4.dp, bottom = 5.dp))
}

/** A small action written as words in the accent colour, for inside a sheet. */
@Composable
private fun TextLink(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MellowTheme.type.callout,
        color = MellowTheme.colors.accent,
        modifier = Modifier.pressable(onClick = onClick, pressedScale = 0.97f).padding(vertical = 5.dp),
    )
}

private val EditCover = 96.dp
private val EditCoverRadius = 22.dp

@Composable
private fun AddToPlaylist(
    trackCount: Int,
    playlists: List<Playlist>,
    onPick: (Playlist) -> Unit,
    onNew: () -> Unit,
) {
    SheetTitle("Add ${songs(trackCount)} to")
    Spacer(Modifier.height(10.dp))

    // Always first, so making a playlist for these songs is never a detour.
    SheetRow(
        onClick = onNew,
        leading = { IconTile(MellowIcons.Add) },
        title = "New playlist",
        subtitle = null,
    )

    // Tall enough for a handful; past that it scrolls inside the sheet.
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 312.dp)) {
        items(playlists, key = { it.id }) { playlist ->
            val covers = remember(playlist.covers) { playlist.covers.map { it.uri } }
            SheetRow(
                onClick = { onPick(playlist) },
                leading = {
                    CoverMosaic(covers, Modifier.size(LibraryDimens.SheetCover), radius = LibraryDimens.SheetCoverRadius)
                },
                title = playlist.name,
                subtitle = songs(playlist.trackCount),
            )
        }
    }
}

@Composable
private fun NamePlaylist(asked: LibrarySheet.Name, onSave: (String) -> Unit) {
    val renaming = asked.renaming
    // A name being changed starts out selected: typing replaces it, a tap keeps it.
    var name by remember(asked) {
        val current = renaming?.name.orEmpty()
        mutableStateOf(TextFieldValue(current, selection = TextRange(0, current.length)))
    }
    val focus = remember { FocusRequester() }
    // The only thing to do on this sheet is type, so the keyboard comes with it.
    LaunchedEffect(asked) { focus.requestFocus() }

    SheetTitle(if (renaming != null) "Rename playlist" else "New playlist")
    Spacer(Modifier.height(14.dp))
    TextField(
        value = name,
        onValueChange = { name = it },
        label = "Playlist name",
        placeholder = "Playlist name",
        onDone = { onSave(name.text) },
        modifier = Modifier.focusRequester(focus),
    )
    Spacer(Modifier.height(14.dp))
    Button(
        text = when {
            renaming != null -> "Save name"
            asked.trackIds.isEmpty() -> "Create playlist"
            else -> "Create and add ${songs(asked.trackIds.size)}"
        },
        onClick = { onSave(name.text) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ConfirmDelete(playlist: Playlist, onDelete: () -> Unit, onKeep: () -> Unit) {
    SheetTitle("Delete “${playlist.name}”?")
    Spacer(Modifier.height(6.dp))
    Text(
        text = when (playlist.trackCount) {
            0 -> "The playlist will be gone for good."
            1 -> "The playlist will be gone for good. Its 1 song stays on the phone."
            else -> "The playlist will be gone for good. Its ${playlist.trackCount} songs stay on the phone."
        },
        color = MellowTheme.colors.inkMuted,
    )
    Spacer(Modifier.height(18.dp))
    Button("Delete playlist", onClick = onDelete, kind = ButtonKind.Destructive, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(10.dp))
    Button("Keep playlist", onClick = onKeep, kind = ButtonKind.Quiet, modifier = Modifier.fillMaxWidth())
}

/**
 * Three ways to give a playlist its cover: let it follow the songs, pick a picture from
 * the phone, or borrow the cover of one of its songs. Whatever is chosen now is ticked.
 */
@Composable
private fun ChooseCover(
    current: PlaylistCover,
    tracks: List<Track>,
    onAutomatic: () -> Unit,
    onPicture: () -> Unit,
    onTrack: (String) -> Unit,
) {
    val colors = MellowTheme.colors
    SheetTitle("Playlist cover")
    Spacer(Modifier.height(10.dp))
    SheetRow(
        onClick = onAutomatic,
        leading = { IconTile(MellowIcons.EmptyPlaylist) },
        title = "Made from its songs",
        subtitle = "Changes as the playlist does",
        chosen = current is PlaylistCover.Automatic,
    )
    SheetRow(
        onClick = onPicture,
        leading = { IconTile(MellowIcons.Picture) },
        title = "A picture from this phone",
        subtitle = if (current is PlaylistCover.Picture) "Tap to choose a different one" else "Opens your photos",
        chosen = current is PlaylistCover.Picture,
    )

    // One tile per cover, not per song: an album's songs all share theirs.
    val songCovers = remember(tracks) { tracks.distinctBy { it.artwork.uri } }
    if (songCovers.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text("Or the cover of one of its songs", style = MellowTheme.type.callout, color = colors.inkMuted)
        Spacer(Modifier.height(10.dp))
        val chosenId = (current as? PlaylistCover.FromTrack)?.trackId
        val chosenUri = remember(tracks, chosenId) { tracks.firstOrNull { it.id == chosenId }?.artwork?.uri }
        val ring = remember { ContinuousCornerShape(SongCoverRadius + RingGap) }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(songCovers, key = { it.id }) { track ->
                val chosen = track.artwork.uri == chosenUri
                Box(
                    modifier = Modifier
                        .pressable(onClick = { onTrack(track.id) }, onClickLabel = "Use the cover of ${track.title}", pressedScale = 0.94f)
                        // The ring is always given its room, so choosing one does not nudge the rest.
                        .drawBehind {
                            if (chosen) {
                                drawOutline(ring.createOutline(size, layoutDirection, this), colors.accent, style = Stroke(RingWidth.toPx()))
                            }
                        }
                        .padding(RingGap),
                ) {
                    CoverArt(track.artwork.uri, Modifier.size(SongCover), shape = remember { ContinuousCornerShape(SongCoverRadius) })
                }
            }
        }
    }
}

/**
 * The things to do with the selected songs that change the songs themselves. They are
 * kept off the bar, one to a row here, each with a line saying what it will do: these are
 * not for doing by a slip of the thumb.
 */
@Composable
private fun MoreActions(
    count: Int,
    inPlaylist: Boolean,
    canDelete: Boolean,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onDelete: () -> Unit,
) {
    val one = count == 1
    SheetTitle(if (one) "This song" else "These $count songs")
    Spacer(Modifier.height(10.dp))
    SheetRow(
        onClick = onEdit,
        leading = { IconTile(MellowIcons.Rename) },
        title = "Edit",
        subtitle = if (one) "Change its name and its cover" else "Give them all one cover",
    )
    if (inPlaylist) {
        SheetRow(
            onClick = onRemove,
            leading = { IconTile(MellowIcons.RemoveFromPlaylist) },
            title = "Remove from this playlist",
            subtitle = if (one) "The song stays on the phone" else "The songs stay on the phone",
        )
    }
    if (canDelete) {
        SheetRow(
            onClick = onDelete,
            leading = { IconTile(MellowIcons.Delete, tint = MellowTheme.colors.danger) },
            title = "Delete from phone",
            subtitle = if (one) "The file is removed for good" else "The files are removed for good",
            titleColor = MellowTheme.colors.danger,
        )
    }
}

/** The square at the start of a sheet row when it stands for an action rather than a thing. */
@Composable
private fun IconTile(icon: ImageVector, tint: Color = MellowTheme.colors.accent) {
    val colors = MellowTheme.colors
    val tile = remember { ContinuousCornerShape(LibraryDimens.SheetCoverRadius) }
    Box(
        modifier = Modifier
            .size(LibraryDimens.SheetCover)
            .drawBehind { drawOutline(tile.createOutline(size, layoutDirection, this), colors.wash) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, size = 22.dp)
    }
}

private val SongCover = 68.dp
private val SongCoverRadius = 16.dp
private val RingGap = 4.dp
private val RingWidth = 2.dp

@Composable
private fun SheetTitle(text: String) {
    Text(text, style = MellowTheme.type.title, maxLines = 2, modifier = Modifier.semantics { heading() })
}

/**
 * A tappable line in a sheet: something square, a name, and optionally a line under it.
 *
 * @param chosen true for the option that is in force now; it gets a tick at the far end.
 */
@Composable
private fun SheetRow(
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    chosen: Boolean = false,
    titleColor: Color = MellowTheme.colors.ink,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick, pressedScale = 0.98f)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MellowTheme.type.headline, color = titleColor, maxLines = 1)
            if (subtitle != null) {
                Text(subtitle, style = MellowTheme.type.caption, color = MellowTheme.colors.inkMuted, maxLines = 1)
            }
        }
        if (chosen) {
            Icon(MellowIcons.Selected, contentDescription = "Chosen", tint = MellowTheme.colors.accent, size = 20.dp)
        }
    }
}
