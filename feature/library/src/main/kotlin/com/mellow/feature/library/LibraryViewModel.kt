package com.mellow.feature.library

import android.content.IntentSender
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mellow.core.data.LibraryRepository
import com.mellow.core.data.PlaylistRepository
import com.mellow.core.data.paging.Paged
import com.mellow.core.data.paging.Pager
import com.mellow.core.model.Playlist
import com.mellow.core.model.PlaylistCover
import com.mellow.core.model.Track
import com.mellow.core.playback.PlaybackController
import com.mellow.core.playback.QueueSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Everything to do with the user's own collection: the playlists, the one that is open,
 * the sheets that ask about them, and what can be done to tracks that have been selected
 * anywhere in the app (put them in a playlist, take them out of one, delete their files).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val library: LibraryRepository,
    private val playlists: PlaylistRepository,
    private val playback: PlaybackController,
) : ViewModel() {

    /**
     * The sheet that is up, if any. Compose state rather than a flow because the sheet's
     * own text field lives off it, and a text field has to hear back in the same frame.
     */
    var sheet: LibrarySheet? by mutableStateOf(null)
        private set

    /** The songs the edit sheet is about, once they have been read. Null before that. */
    var editing: EditingSongs? by mutableStateOf(null)
        private set

    private val _events = Channel<LibraryEvent>(Channel.BUFFERED)

    /** Things that just happened, each delivered once. */
    val events: Flow<LibraryEvent> = _events.receiveAsFlow()

    private val openId = MutableStateFlow<String?>(null)

    /** Fires whenever a playlist could look different: one was edited, or the phone's music changed. */
    private val changes: Flow<Unit> = combine(playlists.revision, library.revision, library.access) { _, _, _ -> }

    private val allPlaylists: Flow<List<Playlist>?> = flow {
        emit(null)
        emitAll(changes.mapLatest { playlists.all() })
    }

    /**
     * The open playlist's tracks, read a page at a time. A playlist is never read whole to
     * be shown: the first page arrives when it is opened, and each further one only when
     * the list is scrolled near the end of what it has.
     */
    private var pager: Pager<Track>? = null
    private var pagerJob: Job? = null
    private val openTracks = MutableStateFlow<OpenTracks?>(null)

    val uiState: StateFlow<LibraryUiState> =
        combine(allPlaylists, openId, openTracks, playback.state) { all, id, loaded, playback ->
            val playlist = id?.let { wanted -> all?.firstOrNull { it.id == wanted } }
            // Tracks read for another playlist are not this one's; show it as loading instead.
            val page = loaded?.takeIf { it.playlistId == playlist?.id }?.page?.takeIf { it.loaded }
            LibraryUiState(
                playlists = all,
                open = playlist?.let {
                    OpenPlaylist(
                        playlist = it,
                        tracks = page?.items,
                        moreTracks = page != null && !page.complete,
                        // Playing from here, not merely a song of this playlist playing from elsewhere.
                        playing = playback.isPlaying && playback.source == QueueSource.playlist(it.id),
                    )
                },
                shuffle = playback.shuffle,
                currentTrackId = playback.trackId,
                isPlaying = playback.isPlaying,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    /** Whether this phone lets Mellow ask to delete audio files. */
    val canDeleteFiles: Boolean get() = library.canDeleteFiles

    // ── Moving around ───────────────────────────────────────────────────────

    fun openPlaylist(playlistId: String) {
        openId.value = playlistId
        pagerJob?.cancel()
        val pages = Pager(viewModelScope, TrackPage, Track::id) { offset, limit -> playlists.tracks(playlistId, offset, limit) }
        pager = pages
        pagerJob = viewModelScope.launch {
            launch { pages.state.collect { openTracks.value = OpenTracks(playlistId, it) } }
            // Reads the first page now, and re-reads what is showing whenever the playlist
            // or the phone's music changes.
            changes.collectLatest { pages.refresh() }
        }
    }

    fun closePlaylist() {
        openId.value = null
        pagerJob?.cancel()
        pager = null
        openTracks.value = null
    }

    /** The playlist's list is scrolled near the end of what has been read. */
    fun onPlaylistNearEnd() {
        pager?.loadMore()
    }

    // ── Playing ─────────────────────────────────────────────────────────────

    /**
     * The big button on a playlist. If the playlist is what is on, it pauses or resumes.
     * Otherwise it starts the playlist: from the top in order, or from anywhere when
     * shuffle is on.
     */
    fun onPlayClick() {
        val open = uiState.value.open ?: return
        val id = open.playlist.id
        val loaded = open.tracks.orEmpty()
        if (loaded.isEmpty()) return
        val current = playback.state.value
        val source = QueueSource.playlist(id)
        when {
            // This playlist is what is on: the button is play and pause.
            current.track != null && current.source == source -> playback.togglePlayPause()
            // A shuffle has to be of the whole playlist, so this is the one time all of
            // it is read: still only descriptions, and only when asked to play.
            current.shuffle -> viewModelScope.launch {
                playback.play(playlists.tracks(id), startIndex = null, source = source)
            }
            // In order, what is loaded is enough to start; the player is told where the
            // rest comes from and fetches it as it gets there.
            else -> playback.play(loaded, startIndex = 0, tail = restOf(id), source = source)
        }
    }

    /** Tapping the track that is already on pauses or resumes it; any other starts the playlist there. */
    fun onTrackClick(trackId: String) {
        if (playback.state.value.trackId == trackId) {
            playback.togglePlayPause()
            return
        }
        val open = uiState.value.open ?: return
        val loaded = open.tracks.orEmpty()
        val index = loaded.indexOfFirst { it.id == trackId }
        if (index >= 0) {
            playback.play(loaded, index, tail = restOf(open.playlist.id), source = QueueSource.playlist(open.playlist.id))
        }
    }

    /** Where a queue started from part of a playlist gets the rest: the next page each time it runs low. */
    private fun restOf(playlistId: String): suspend (Int) -> List<Track> =
        { alreadyQueued -> playlists.tracks(playlistId, offset = alreadyQueued, limit = TrackPage) }

    fun onShuffleToggle() = playback.toggleShuffle()

    // ── Sheets ──────────────────────────────────────────────────────────────

    fun dismissSheet() {
        sheet = null
    }

    /** Asks for a name for a new playlist. [trackIds] go straight into it once it is named. */
    fun askToCreate(trackIds: Set<String> = emptySet()) {
        sheet = LibrarySheet.Name(renaming = null, trackIds = trackIds)
    }

    /** Asks which playlist [trackIds] should go into. */
    fun askToAdd(trackIds: Set<String>) {
        if (trackIds.isNotEmpty()) sheet = LibrarySheet.AddTo(trackIds)
    }

    fun askToRenameOpen() {
        uiState.value.open?.let { sheet = LibrarySheet.Name(renaming = it.playlist) }
    }

    fun askToDeleteOpen() {
        uiState.value.open?.let { sheet = LibrarySheet.ConfirmDelete(it.playlist) }
    }

    fun askToChangeCoverOfOpen() {
        uiState.value.open?.let { sheet = LibrarySheet.Cover(it.playlist.id) }
    }

    /** Opens the edit sheet for the selected songs: names and cover for one, cover for several. */
    fun askToEdit(trackIds: Set<String>) {
        if (trackIds.isEmpty()) return
        val asked = LibrarySheet.EditSongs(trackIds)
        editing = null
        sheet = asked
        viewModelScope.launch {
            val shown = library.tracksById(trackIds).associateBy(Track::id)
            val filed = library.tracksAsFiled(trackIds)
            // In the order they were picked, which is the order the user thinks of them in.
            val ordered = trackIds.mapNotNull(shown::get)
            val mates = ordered.singleOrNull()?.let { library.albumTracks(it.albumId).size - 1 } ?: 0
            if (sheet == asked) editing = EditingSongs(ordered, filed, mates.coerceAtLeast(0))
        }
    }

    // ── Changing things ─────────────────────────────────────────────────────

    /** The name sheet's button. A blank name is not an error; the playlist just gets a plain one. */
    fun saveName(typed: String) {
        val asked = sheet as? LibrarySheet.Name ?: return
        sheet = null
        viewModelScope.launch {
            val renaming = asked.renaming
            if (renaming != null) {
                val name = typed.trim().ifEmpty { renaming.name }
                if (name != renaming.name) {
                    playlists.rename(renaming.id, name)
                    say("Renamed to $name")
                }
            } else {
                val name = typed.trim().ifEmpty { unusedDefaultName() }
                val created = playlists.create(name)
                if (asked.trackIds.isEmpty()) {
                    say("Created $name")
                } else {
                    val added = playlists.add(created.id, asked.trackIds)
                    say("Created $name with ${songs(added)}", selectionUsed = true)
                }
            }
        }
    }

    fun addTo(playlist: Playlist, trackIds: Set<String>) {
        sheet = null
        viewModelScope.launch {
            val added = playlists.add(playlist.id, trackIds)
            val skipped = trackIds.size - added
            say(
                message = when {
                    skipped == 0 -> "Added ${songs(added)} to ${playlist.name}"
                    added == 0 && skipped == 1 -> "Already in ${playlist.name}"
                    added == 0 -> "All ${songs(skipped)} are already in ${playlist.name}"
                    else -> "Added ${songs(added)} to ${playlist.name}. ${if (skipped == 1) "1 was" else "$skipped were"} already there."
                },
                selectionUsed = true,
            )
        }
    }

    /** Takes tracks out of the open playlist. Their files stay where they are. */
    fun removeFromOpen(trackIds: Set<String>) {
        val open = uiState.value.open ?: return
        viewModelScope.launch {
            playlists.remove(open.playlist.id, trackIds)
            say("Removed ${songs(trackIds.size)} from ${open.playlist.name}", selectionUsed = true)
        }
    }

    /** The confirmation sheet's button. */
    fun deleteConfirmed() {
        val asked = sheet as? LibrarySheet.ConfirmDelete ?: return
        sheet = null
        if (openId.value == asked.playlist.id) closePlaylist()
        viewModelScope.launch {
            playlists.delete(asked.playlist.id)
            say("Deleted ${asked.playlist.name}")
        }
    }

    // ── Covers ──────────────────────────────────────────────────────────────

    /** Back to a cover made from the playlist's songs. */
    fun useAutomaticCover() = changeCover { playlists.setCover(it, PlaylistCover.Automatic) }

    /** One song's cover stands for the whole playlist. */
    fun useTrackCover(trackId: String) = changeCover { playlists.setCover(it, PlaylistCover.FromTrack(trackId)) }

    /** A picture from the phone, as handed over by the system's picker. */
    fun usePictureCover(pictureUri: String) = changeCover { playlistId ->
        if (!playlists.setCoverPicture(playlistId, pictureUri)) {
            say("That picture could not be opened. Choose a different one.")
        }
    }

    private fun changeCover(change: suspend (playlistId: String) -> Unit) {
        val asked = sheet as? LibrarySheet.Cover ?: return
        sheet = null
        viewModelScope.launch { change(asked.playlistId) }
    }

    // ── Editing songs ───────────────────────────────────────────────────────

    /**
     * The edit sheet's button, for one song. Nothing is written to the audio file: the
     * names and the cover are Mellow's own, laid over what the file says.
     *
     * @param wholeAlbum also give a newly chosen picture to the other songs of its album.
     */
    fun saveSongEdit(title: String, artist: String, album: String, picture: PictureChange, wholeAlbum: Boolean) {
        val song = editing?.single ?: return
        if (sheet !is LibrarySheet.EditSongs) return
        sheet = null
        viewModelScope.launch {
            library.rename(song.id, title, artist, album)
            var touched = listOf(song.id)
            var unreadable = false
            when (picture) {
                PictureChange.Keep -> Unit
                PictureChange.UseItsOwn -> library.clearCoverPicture(touched)
                is PictureChange.Use -> {
                    if (wholeAlbum) touched = (library.albumTracks(song.albumId).map(Track::id) + song.id).distinct()
                    unreadable = !library.setCoverPicture(touched, picture.pictureUri)
                }
            }
            playback.refresh(library.tracksById(touched))
            say(
                message = when {
                    unreadable -> "Saved the names. That picture could not be opened, so the cover is unchanged."
                    touched.size > 1 -> "Saved, and gave the picture to ${songs(touched.size)}"
                    else -> "Saved your changes to ${title.trim().ifEmpty { song.title }}"
                },
                selectionUsed = true,
            )
        }
    }

    /** One picture for every song in the edit sheet. */
    fun useCoverForEdited(pictureUri: String) {
        val ids = (sheet as? LibrarySheet.EditSongs)?.trackIds ?: return
        sheet = null
        viewModelScope.launch {
            if (library.setCoverPicture(ids, pictureUri)) {
                playback.refresh(library.tracksById(ids))
                say("Changed the cover of ${songs(ids.size)}", selectionUsed = true)
            } else {
                say("That picture could not be opened. Choose a different one.")
            }
        }
    }

    /** Back to their albums' covers, for every song in the edit sheet. */
    fun useOwnCoversForEdited() {
        val ids = (sheet as? LibrarySheet.EditSongs)?.trackIds ?: return
        sheet = null
        viewModelScope.launch {
            library.clearCoverPicture(ids)
            playback.refresh(library.tracksById(ids))
            say(if (ids.size == 1) "Back to its own cover" else "${songs(ids.size)} are back on their own covers", selectionUsed = true)
        }
    }

    /** Forgets everything edited about the song in the edit sheet. */
    fun undoEditsOfEdited() {
        val ids = (sheet as? LibrarySheet.EditSongs)?.trackIds ?: return
        sheet = null
        viewModelScope.launch {
            library.undoEdits(ids)
            playback.refresh(library.tracksById(ids))
            say("Put back as the file has it", selectionUsed = true)
        }
    }

    // ── Deleting files ──────────────────────────────────────────────────────

    /** Android's own "delete these files?" screen for [trackIds], to be launched for a result. */
    fun deleteFilesRequest(trackIds: Set<String>): IntentSender? = library.deleteRequest(trackIds)

    /** The user agreed and Android deleted the files. Clears up what pointed at them. */
    fun onFilesDeleted(trackIds: Set<String>) {
        playback.removeFromQueue(trackIds)
        viewModelScope.launch {
            playlists.forget(trackIds)
            library.undoEdits(trackIds)
            say("Deleted ${songs(trackIds.size)} from this phone", selectionUsed = true)
        }
    }

    private fun say(message: String, selectionUsed: Boolean = false) {
        _events.trySend(LibraryEvent(message, selectionUsed))
    }

    /** "New playlist", or "New playlist 2" and so on if that is taken. */
    private fun unusedDefaultName(): String {
        val taken = uiState.value.playlists.orEmpty().mapTo(HashSet(), Playlist::name)
        if (DefaultName !in taken) return DefaultName
        var number = 2
        while ("$DefaultName $number" in taken) number++
        return "$DefaultName $number"
    }

    private class OpenTracks(val playlistId: String, val page: Paged<Track>)

    companion object {
        private const val DefaultName = "New playlist"

        /** A few screens of a playlist at a time. */
        private const val TrackPage = 40

        fun factory(
            library: LibraryRepository,
            playlists: PlaylistRepository,
            playback: PlaybackController,
        ): ViewModelProvider.Factory =
            viewModelFactory { initializer { LibraryViewModel(library, playlists, playback) } }
    }
}

/** "1 song", "4 songs". */
internal fun songs(count: Int): String = if (count == 1) "1 song" else "$count songs"
