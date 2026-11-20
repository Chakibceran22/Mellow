package com.mellow.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mellow.core.data.LibraryRepository
import com.mellow.core.data.paging.Pager
import com.mellow.core.model.Album
import com.mellow.core.model.Track
import com.mellow.core.playback.PlaybackController
import com.mellow.core.playback.QueueSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Home never holds the library. It holds two windows onto it, tracks and albums, each as
 * long as the user has scrolled, and widens one when the screen says its end is near.
 */
class HomeViewModel(
    private val library: LibraryRepository,
    private val playback: PlaybackController,
) : ViewModel() {

    private val tracks = Pager(viewModelScope, TrackPage, Track::id, library::tracks)
    private val albums = Pager(viewModelScope, AlbumPage, Album::id, library::albums)

    /** The album at the top of the page. [Featured.known] is false until the first one has been looked up. */
    private val featured = MutableStateFlow(Featured())

    init {
        // Read the first pages once allowed, and again whenever the phone's music changes.
        viewModelScope.launch {
            combine(library.access, library.revision) { access, _ -> access }.collectLatest { access ->
                if (access) {
                    tracks.refresh()
                    albums.refresh()
                } else {
                    tracks.clear()
                    albums.clear()
                    featured.value = Featured()
                }
            }
        }
        // The featured album follows the newest track.
        viewModelScope.launch {
            tracks.state
                .map { it.items.firstOrNull() }
                .distinctUntilChanged { old, new -> old?.albumId == new?.albumId }
                .collectLatest { newest ->
                    if (newest != null) featured.value = Featured(known = true, album = library.album(newest.albumId))
                }
        }
    }

    private val _reloading = MutableStateFlow(false)

    /** True from the moment a reload is asked for until the page has what it found. */
    val reloading: StateFlow<Boolean> = _reloading.asStateFlow()

    private val loaded: Flow<HomeUiState> =
        combine(library.access, tracks.state, albums.state, featured, playback.state) { access, tracks, albums, featured, playback ->
            val newest = tracks.items.firstOrNull()
            when {
                !access -> HomeUiState.NeedsAccess
                !tracks.loaded || !albums.loaded -> HomeUiState.Loading
                newest == null -> HomeUiState.Empty
                // One more beat of skeleton rather than a page that shows up without its top card.
                !featured.known -> HomeUiState.Loading
                else -> HomeUiState.Ready(
                    featured = featured.album ?: newest.asAlbum(),
                    albums = albums.items,
                    moreAlbums = !albums.complete,
                    tracks = tracks.items,
                    moreTracks = !tracks.complete,
                    currentTrackId = playback.track?.id,
                    playingFrom = playback.source,
                    isPlaying = playback.isPlaying,
                )
            }
        }

    val uiState: StateFlow<HomeUiState> =
        combine(loaded, _reloading) { state, reloading ->
            // While looking again, the page shows its skeleton: what is on it may be
            // about to change, and the skeleton says so better than a frozen list.
            if (reloading && state !is HomeUiState.NeedsAccess) HomeUiState.Loading else state
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState.Loading)

    /**
     * Has the phone look again for music, for when a song that was just saved is not
     * showing. The skeleton stays up for a moment even when the answer is instant, so a
     * press is always seen to have done something.
     */
    fun onReload() {
        if (_reloading.value) return
        _reloading.value = true
        viewModelScope.launch {
            val started = System.currentTimeMillis()
            library.rescan()
            delay((ReloadShownMs - (System.currentTimeMillis() - started)).coerceAtLeast(0))
            _reloading.value = false
        }
    }

    /** The user answered the permission question, one way or the other. */
    fun onAccessAnswered() = library.refreshAccess()

    /** The track list is scrolled near the end of what has been read. */
    fun onTracksNearEnd() = tracks.loadMore()

    fun onAlbumsNearEnd() = albums.loadMore()

    /**
     * Tapping the track that is already on pauses or resumes it; any other starts it.
     *
     * The queue is the list as far as it has been read, and the player is told where the
     * rest comes from, so the music carries on past the last row on screen without the
     * whole library ever being queued at once.
     */
    fun onTrackClick(trackId: String) {
        if (playback.state.value.trackId == trackId) {
            playback.togglePlayPause()
            return
        }
        val loaded = tracks.state.value.items
        val index = loaded.indexOfFirst { it.id == trackId }
        if (index >= 0) {
            playback.play(
                queue = loaded,
                startIndex = index,
                tail = { already -> library.tracks(offset = already, limit = TrackPage) },
                source = QueueSource.Library,
            )
        }
    }

    /** For albums: pause or resume if the album is what is on, otherwise play it from the top. */
    fun onAlbumClick(albumId: String) {
        val source = QueueSource.album(albumId)
        val current = playback.state.value
        if (current.track != null && current.source == source) {
            playback.togglePlayPause()
            return
        }
        viewModelScope.launch {
            val albumTracks = library.albumTracks(albumId)
            if (albumTracks.isNotEmpty()) playback.play(albumTracks, source = source)
        }
    }

    /** Enough to show the card if the album's own row cannot be read. */
    private fun Track.asAlbum() = Album(id = albumId, title = album, artist = artist, artwork = artwork, trackCount = 1)

    private data class Featured(val known: Boolean = false, val album: Album? = null)

    companion object {
        /** A few screens' worth. Small enough to read in a blink, large enough that a scroll rarely catches up. */
        private const val TrackPage = 60
        private const val AlbumPage = 24

        /** The least time the reload skeleton is shown. */
        private const val ReloadShownMs = 900L

        fun factory(library: LibraryRepository, playback: PlaybackController): ViewModelProvider.Factory =
            viewModelFactory { initializer { HomeViewModel(library, playback) } }
    }
}
