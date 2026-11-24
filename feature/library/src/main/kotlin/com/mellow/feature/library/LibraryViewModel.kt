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
import com.mellow.core.data.SongChanges
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

    /** The songs the edit sheet is about. Read before the sheet is shown, and kept while it slides away. */
    var editing: EditingSongs? by mutableStateOf(null)
        private set

    private var editJob: Job? = null

    /**
     * Android's own question about changing song files ("Allow Mellow to modify these
     * audio files?"), waiting to be put to the user. Only an activity can put it, so the
     * shell watches this: it calls [onWriteQuestionShown] as it launches the question and
     * [onWriteAnswered] with what the user said.
     */
    var writeQuestion: IntentSender? by mutableStateOf(null)
        private set

    /** What is to be done to which songs once the question is answered. */
    private var awaitingAnswer: WriteToSongs? = null

    /** How many songs the user was last asked about moving covers into, so as not to ask twice about the same ones. */
    private var askedToMove = 0

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
                playback.play(playlists.tracks(id), startIndex = null, source = source, sourceLabel = open.playlist.name)
            }
            // In order, what is loaded is enough to start; the player is told where the
            // rest comes from and fetches it as it gets there.
            else -> playback.play(loaded, startIndex = 0, tail = restOf(id), source = source, sourceLabel = open.playlist.name)
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
            playback.play(
                queue = loaded,
                startIndex = index,
                tail = restOf(open.playlist.id),
                source = QueueSource.playlist(open.playlist.id),
                sourceLabel = open.playlist.name,
            )
        }
    }

    /** Where a queue started from part of a playlist gets the rest: the next page each time it runs low. */
    private fun restOf(playlistId: String): suspend (Int) -> List<Track> =
        { alreadyQueued -> playlists.tracks(playlistId, offset = alreadyQueued, limit = TrackPage) }

    fun onShuffleToggle() = playback.toggleShuffle()

    init {
        // Each time the library is read afresh: at the start, and when a backup brings covers in.
        viewModelScope.launch { library.revision.collect { offerToMovePictures() } }
    }

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

    /** Opens the sheet of everything else that can be done with [trackIds]. */
    fun askForMore(trackIds: Set<String>, inPlaylist: Boolean) {
        if (trackIds.isNotEmpty()) sheet = LibrarySheet.More(trackIds, inPlaylist)
    }

    // ── The queue ───────────────────────────────────────────────────────────

    /** Puts these songs at the front of the user's queue: the first plays as soon as the current song ends. */
    fun playNext(trackIds: Set<String>) = queue(trackIds, atFront = true)

    /** Puts these songs at the back of the user's queue. */
    fun addToQueue(trackIds: Set<String>) = queue(trackIds, atFront = false)

    private fun queue(trackIds: Set<String>, atFront: Boolean) {
        if (trackIds.isEmpty()) return
        viewModelScope.launch {
            val found = library.tracksById(trackIds).associateBy(Track::id)
            // In the order they were picked, which is the order they were asked to play in.
            val tracks = trackIds.mapNotNull(found::get)
            if (tracks.isEmpty()) {
                say(if (trackIds.size == 1) "That song is no longer on this phone" else "Those songs are no longer on this phone", selectionUsed = true)
                return@launch
            }
            val on = playback.state.value.trackId
            // The song that is on cannot be queued behind itself, and is left out.
            val joining = tracks.filter { it.id != on }
            if (atFront) playback.playNext(tracks) else playback.addToQueue(tracks)
            say(
                message = when {
                    on == null -> if (tracks.size == 1) "Playing ${tracks[0].title}" else "Playing ${songs(tracks.size)}"
                    joining.isEmpty() -> "That song is already playing"
                    atFront -> if (joining.size == 1) "${joining[0].title} plays next" else "${songs(joining.size)} play next"
                    else -> if (joining.size == 1) "Added ${joining[0].title} to your queue" else "Added ${songs(joining.size)} to your queue"
                },
                selectionUsed = true,
            )
        }
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

    /**
     * Opens the edit sheet for the selected songs: names and cover for one, cover for several.
     *
     * The songs are read first and the sheet is shown once they are known, so that it
     * comes up whole. Shown at once and filled in when the songs arrived, it rose as a
     * bare title and jumped to its full height half way up.
     */
    fun askToEdit(trackIds: Set<String>) {
        if (trackIds.isEmpty()) return
        editJob?.cancel()
        editJob = viewModelScope.launch {
            val shown = library.tracksById(trackIds).associateBy(Track::id)
            val filed = library.tracksAsFiled(trackIds)
            // In the order they were picked, which is the order the user thinks of them in.
            val ordered = trackIds.mapNotNull(shown::get)
            if (ordered.isEmpty()) {
                // Deleted from outside Mellow since they were picked.
                say(if (trackIds.size == 1) "That song is no longer on this phone" else "Those songs are no longer on this phone", selectionUsed = true)
                return@launch
            }
            val mates = ordered.singleOrNull()?.let { library.albumTracks(it.albumId).size - 1 } ?: 0
            val fromMellow = library.changesFromMellow(trackIds)
            // Another question may have come up while these were read. It keeps the screen.
            // (The sheet of more things to do is where this one is asked for; it gives way.)
            if (sheet != null && sheet !is LibrarySheet.More) return@launch
            editing = EditingSongs(ordered, filed, mates.coerceAtLeast(0), fromMellow.pictures, fromMellow.all)
            sheet = LibrarySheet.EditSongs(trackIds)
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
        if (sheet is LibrarySheet.More) sheet = null
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
     * The edit sheet's button, for one song. What was changed is written into the song's
     * own file, names and cover alike, so it is true of the song to Android and to every
     * other app. Android asks the user before any of it happens.
     *
     * @param wholeAlbum also give a newly chosen picture to the other songs of its album.
     */
    fun saveSongEdit(title: String, artist: String, album: String, picture: PictureChange, wholeAlbum: Boolean) {
        val song = editing?.single ?: return
        if (sheet !is LibrarySheet.EditSongs) return
        sheet = null
        if (!library.canWriteToSongs) {
            saveInMellow(song, title, artist, album, picture, wholeAlbum)
            return
        }
        fun differs(typed: String, shown: String) = typed.trim().let { it.isNotEmpty() && it != shown }
        val renamed = differs(title, song.title) || differs(artist, song.artist) || differs(album, song.album)
        if (!renamed && picture == PictureChange.Keep) {
            say("Nothing was changed", selectionUsed = true)
            return
        }
        viewModelScope.launch {
            val newPicture = (picture as? PictureChange.Use)?.pictureUri
            val mates = if (wholeAlbum && newPicture != null) library.albumTracks(song.albumId).map(Track::id).filter { it != song.id } else emptyList()
            val touched = listOf(song.id) + mates
            writeToSongs(touched, ifRefused = "Nothing was changed. The song is as it was.") {
                var done = SongChanges()
                if (renamed || newPicture != null) done += library.writeSongDetails(song.id, title, artist, album, newPicture)
                if (newPicture != null && mates.isNotEmpty() && !done.pictureUnreadable) done += library.writeCoverPicture(mates, newPicture)
                if (picture == PictureChange.UseItsOwn) done += library.restoreCoverPicture(listOf(song.id))
                playback.refresh(library.tracksById(touched))
                say(
                    message = when {
                        done.pictureUnreadable -> "That picture could not be opened. Nothing was changed."
                        done.changed == 0 -> "The song could not be changed. It is as it was."
                        done.written == 0 -> "Saved in Mellow only: this kind of file cannot be changed"
                        mates.isNotEmpty() -> "Saved, and wrote the picture into ${songs(done.written)}" + leftOut(done)
                        picture == PictureChange.UseItsOwn && !renamed -> "Put back the picture it came with"
                        else -> "Saved into the song itself"
                    },
                    selectionUsed = true,
                )
            }
        }
    }

    /** The same save where Android gives an app no way to write to music: everything stays in Mellow. */
    private fun saveInMellow(song: Track, title: String, artist: String, album: String, picture: PictureChange, wholeAlbum: Boolean) {
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
        givePicture(ids.toList(), pictureUri)
    }

    /**
     * The cover one of the songs in the edit sheet wears, given to the rest of them. The
     * song it comes from is left alone: it has that picture already, and writing it back
     * into its own file would only make it a copy of itself.
     */
    fun useCoverOfEdited(trackId: String) {
        if (sheet !is LibrarySheet.EditSongs) return
        val tracks = editing?.tracks ?: return
        val from = tracks.firstOrNull { it.id == trackId } ?: return
        val others = tracks.filter { it.artwork.uri != from.artwork.uri }.map(Track::id)
        sheet = null
        if (others.isEmpty()) {
            say("They already share this cover", selectionUsed = true)
            return
        }
        givePicture(others, from.artwork.uri)
    }

    /** Back to the pictures they came with, for every song in the edit sheet. */
    fun useOwnCoversForEdited() {
        val ids = (sheet as? LibrarySheet.EditSongs)?.trackIds ?: return
        sheet = null
        viewModelScope.launch { giveBackPictures(ids.toList()) }
    }

    /** Returns the song in the edit sheet to exactly what it was before Mellow changed it: its names and its picture. */
    fun undoEditsOfEdited() {
        val ids = (sheet as? LibrarySheet.EditSongs)?.trackIds ?: return
        sheet = null
        viewModelScope.launch {
            val changes = library.changesFromMellow(ids)
            if (changes.all.isEmpty()) {
                say("There is nothing to undo", selectionUsed = true)
                return@launch
            }
            suspend fun undo() {
                val done = library.restoreSongs(ids)
                playback.refresh(library.tracksById(ids))
                say(if (done.written > 0) "Put back as it came" else "It could not be put back. Nothing was changed.", selectionUsed = true)
            }
            // Only a change that is in a file needs Android's leave to take out again.
            if (changes.written.isEmpty()) undo() else writeToSongs(changes.written, ifRefused = "Nothing was changed. The song is as it was.") { undo() }
        }
    }

    /**
     * Makes a picture the cover of these songs: inside the songs' own files where Android
     * lets an app write to them, after asking; kept in Mellow where it does not.
     */
    private fun givePicture(ids: List<String>, pictureUri: String) {
        if (!library.canWriteToSongs) {
            viewModelScope.launch {
                if (library.setCoverPicture(ids, pictureUri)) {
                    playback.refresh(library.tracksById(ids))
                    say(if (ids.size == 1) "Changed the cover" else "Changed the cover of ${songs(ids.size)}", selectionUsed = true)
                } else {
                    say("That picture could not be opened. Choose a different one.")
                }
            }
            return
        }
        writeToSongs(ids, ifRefused = if (ids.size == 1) "The picture was left as it is." else "The pictures were left as they are.") {
            val done = library.writeCoverPicture(ids, pictureUri)
            playback.refresh(library.tracksById(ids))
            say(
                message = when {
                    done.pictureUnreadable -> "That picture could not be opened. Choose a different one."
                    done.changed == 0 -> if (ids.size == 1) "The picture could not be changed. The song is as it was." else "The pictures could not be changed. The songs are as they were."
                    done.written == 0 -> "Changed the cover in Mellow only: ${if (done.keptInMellow == 1) "this kind of file" else "these kinds of file"} cannot hold a picture"
                    ids.size == 1 -> "The picture is now part of the song itself"
                    else -> "Wrote the picture into ${songs(done.written)}" + leftOut(done)
                },
                selectionUsed = true,
            )
        }
    }

    /** Returns to these songs the pictures they had before Mellow changed them. */
    private suspend fun giveBackPictures(ids: List<String>) {
        val changes = library.changesFromMellow(ids)
        if (changes.pictures.isEmpty()) {
            say(if (ids.size == 1) "It already has the picture it came with" else "They already have the pictures they came with", selectionUsed = true)
            return
        }
        suspend fun giveBack() {
            val done = library.restoreCoverPicture(changes.pictures)
            playback.refresh(library.tracksById(changes.pictures))
            say(
                message = when (done.written) {
                    0 -> "The picture could not be put back. Nothing was changed."
                    1 -> "Put back the picture it came with"
                    else -> "Put back the pictures ${songs(done.written)} came with"
                },
                selectionUsed = true,
            )
        }
        // Only a picture that is in a file needs Android's leave to take out again.
        val inFiles = changes.pictures intersect changes.written
        if (inFiles.isEmpty()) giveBack() else writeToSongs(inFiles, ifRefused = "The picture was left as it is.") { giveBack() }
    }

    /** The end of a sentence about several songs, for the ones that did not go as the rest did. */
    private fun leftOut(done: SongChanges): String {
        val kept = if (done.written > 0 && done.keptInMellow > 0) " ${done.keptInMellow} cannot hold one, so Mellow keeps it." else ""
        val failed = if (done.failed > 0) " ${done.failed} could not be changed and ${if (done.failed == 1) "was" else "were"} left alone." else ""
        return if (kept.isEmpty() && failed.isEmpty()) "" else ".$kept$failed"
    }

    // ── Writing to song files ───────────────────────────────────────────────

    /**
     * Does [work], which writes to these songs' files, once the user has told Android it
     * may. Android's question is put by the shell ([writeQuestion]); the answer comes back
     * through [onWriteAnswered].
     *
     * @param ifRefused what to tell the user if they say no.
     */
    private fun writeToSongs(trackIds: Collection<String>, ifRefused: String, work: suspend () -> Unit) {
        val question = library.writeRequest(trackIds)
        if (question == null) {
            say(ifRefused)
            return
        }
        awaitingAnswer = WriteToSongs(trackIds.toSet(), ifRefused, work)
        writeQuestion = question
    }

    /** Whether a chosen cover goes into the song's file on this phone, rather than being kept in Mellow. */
    val writesToSongs: Boolean get() = library.canWriteToSongs

    /** The shell has put [writeQuestion] to the user. It is not to be put again. */
    fun onWriteQuestionShown() {
        writeQuestion = null
    }

    /** What the user told Android about changing the files. */
    fun onWriteAnswered(allowed: Boolean) {
        val asked = awaitingAnswer ?: return
        awaitingAnswer = null
        writeQuestion = null
        if (!allowed) {
            say(asked.ifRefused, selectionUsed = true)
            return
        }
        viewModelScope.launch {
            // A song that is playing is closed while it is rewritten and opened again after.
            playback.letGo(asked.trackIds)
            try {
                asked.work()
            } finally {
                playback.pickUp()
            }
        }
    }

    /**
     * Looks for covers that Mellow is still keeping to itself (chosen before it wrote them
     * into songs, or brought in by a backup from then) and offers, once, to put them where
     * they belong.
     */
    private suspend fun offerToMovePictures() {
        if (!library.canWriteToSongs || !library.access.value || sheet != null || awaitingAnswer != null) return
        val waiting = library.songsAwaitingWrite()
        val count = waiting.all.size
        // Asked about these already, and the answer was "not now".
        if (count <= askedToMove) return
        askedToMove = count
        sheet = LibrarySheet.MovePictures(count, unfinished = waiting.unfinished.size)
    }

    /** The move sheet's button. */
    fun moveWaitingPictures() {
        if (sheet !is LibrarySheet.MovePictures) return
        sheet = null
        viewModelScope.launch {
            val ids = library.songsAwaitingWrite().all
            if (ids.isEmpty()) return@launch
            writeToSongs(ids, ifRefused = "Nothing was written. Mellow will ask again next time it opens.") {
                val done = library.writeAwaited()
                playback.refresh(library.tracksById(ids))
                say(
                    when {
                        done.written == 0 && done.keptInMellow == 0 -> "The changes could not be written. Nothing was changed."
                        done.written == 0 -> "These kinds of file cannot be changed, so Mellow keeps what you changed"
                        else -> {
                            val moved = if (done.written == 1) "Your changes are now part of the song itself" else "Your changes are now part of ${songs(done.written)}"
                            val kept = if (done.keptInMellow > 0) " ${done.keptInMellow} stay in Mellow: that kind of file cannot be changed." else ""
                            val failed = if (done.failed > 0) " ${done.failed} could not be written." else ""
                            if (kept.isEmpty() && failed.isEmpty()) moved else "$moved.$kept$failed"
                        }
                    },
                )
            }
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

    /** Work on song files that is waiting for the user to tell Android it may be done. */
    private class WriteToSongs(val trackIds: Set<String>, val ifRefused: String, val work: suspend () -> Unit)

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
