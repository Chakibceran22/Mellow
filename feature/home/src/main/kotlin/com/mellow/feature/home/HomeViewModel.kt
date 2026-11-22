package com.mellow.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mellow.core.data.LibraryRepository
import com.mellow.core.data.PlaylistRepository
import com.mellow.core.data.paging.Pager
import com.mellow.core.model.Album
import com.mellow.core.model.Playlist
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Home never holds the library. It holds two windows onto it, tracks and albums, each as
 * long as the user has scrolled, and widens one when the screen says its end is near.
 */
class HomeViewModel(
    private val library: LibraryRepository,
    private val playlists: PlaylistRepository,
    private val playback: PlaybackController,
) : ViewModel() {

    private val tracks = Pager(viewModelScope, TrackPage, Track::id, library::tracks)
    private val albums = Pager(viewModelScope, AlbumPage, Album::id, library::albums)

    /**
     * The user's playlists that have something in them, which lead the album shelf.
     * Null until they have been read, so the shelf never shows up and then shifts along.
     */
    private val shelfPlaylists = MutableStateFlow<List<Playlist>?>(null)

    /**
     * What is on the card at the top of the page, by its shelf key. It is one of the user's
     * own playlists, picked at random when the app opens so the page doesn't always lead
     * with the same one, and kept for as long as this lives. It is picked again only if
     * that playlist goes. With no playlists to pick from, an album from the first page of
     * albums stands in, until the user makes a playlist.
     */
    private val featuredKey = MutableStateFlow<String?>(null)

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
                    featuredKey.value = null
                }
            }
        }
        // Playlists change on their own, and lose tracks when the phone's music does.
        viewModelScope.launch {
            combine(library.access, library.revision, playlists.revision) { access, _, _ -> access }.collectLatest { access ->
                shelfPlaylists.value = if (access) playlists.all().filter { it.trackCount > 0 } else null
            }
        }
        viewModelScope.launch {
            combine(albums.state, shelfPlaylists) { page, mine -> page to mine }.collect { (page, mine) ->
                if (!page.loaded || mine == null) return@collect
                val current = featuredKey.value
                // While there are playlists the card has to be one of them; an album only stays while there are none.
                val stillRight = if (mine.isNotEmpty()) {
                    mine.any { QueueSource.playlist(it.id) == current }
                } else {
                    page.items.any { QueueSource.album(it.id) == current }
                }
                if (!stillRight) {
                    featuredKey.value = mine.randomOrNull()?.let { QueueSource.playlist(it.id) }
                        ?: page.items.take(AlbumPage).randomOrNull()?.let { QueueSource.album(it.id) }
                }
            }
        }
    }

    /**
     * The shelf as it should read: the user's playlists first, then the albums on the
     * phone. What they made themselves comes before what merely happens to be there.
     */
    private val shelf: Flow<Shelf?> =
        combine(albums.state, shelfPlaylists) { albums, playlists ->
            if (!albums.loaded || playlists == null) {
                null
            } else {
                Shelf(playlists.map(ShelfItem::OfPlaylist) + albums.items.map(ShelfItem::OfAlbum), more = !albums.complete)
            }
        }

    /**
     * What was picked for the card, and whether the pick has been made. The pick lands a
     * moment after the playlists and albums do; until it has, the page waits, so the card
     * never shows one thing and then swaps to another.
     */
    private val featured: Flow<Featured> =
        combine(albums.state, shelfPlaylists, featuredKey) { albums, mine, key ->
            val playlist = mine?.firstOrNull { QueueSource.playlist(it.id) == key }
            val item = playlist?.let(ShelfItem::OfPlaylist)
                ?: albums.items.firstOrNull { QueueSource.album(it.id) == key }?.let(ShelfItem::OfAlbum)
            val settled = albums.loaded && mine != null && when {
                // There are playlists: nothing but one of them will do.
                mine.isNotEmpty() -> playlist != null
                else -> item != null || albums.items.isEmpty()
            }
            Featured(settled, item)
        }

    private val _reloading = MutableStateFlow(false)

    /** True from the moment a reload is asked for until the page has what it found. */
    val reloading: StateFlow<Boolean> = _reloading.asStateFlow()

    private val loaded: Flow<HomeUiState> =
        combine(library.access, tracks.state, shelf, featured, playback.state) { access, tracks, shelf, featured, playback ->
            val newest = tracks.items.firstOrNull()
            when {
                !access -> HomeUiState.NeedsAccess
                !tracks.loaded || shelf == null -> HomeUiState.Loading
                newest == null -> HomeUiState.Empty
                // One more beat of skeleton rather than a card that changes its mind.
                !featured.settled -> HomeUiState.Loading
                else -> HomeUiState.Ready(
                    // Nothing to pick from at all: the newest track's album is enough for the card.
                    featured = featured.item ?: ShelfItem.OfAlbum(newest.asAlbum()),
                    shelf = shelf.items,
                    moreAlbums = shelf.more,
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
                sourceLabel = "all your songs",
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
            if (albumTracks.isNotEmpty()) playback.play(albumTracks, source = source, sourceLabel = albumTracks.first().album)
        }
    }

    /**
     * For playlists on the shelf: pause or resume if it is what is on, otherwise play it from
     * the top, or all of it shuffled when shuffle is on. Same as the Library's play button.
     */
    fun onPlaylistClick(playlistId: String) {
        val source = QueueSource.playlist(playlistId)
        val current = playback.state.value
        if (current.track != null && current.source == source) {
            playback.togglePlayPause()
            return
        }
        val name = shelfPlaylists.value?.firstOrNull { it.id == playlistId }?.name
        viewModelScope.launch {
            if (current.shuffle) {
                val all = playlists.tracks(playlistId)
                if (all.isNotEmpty()) playback.play(all, startIndex = null, source = source, sourceLabel = name)
            } else {
                val first = playlists.tracks(playlistId, offset = 0, limit = TrackPage)
                if (first.isNotEmpty()) {
                    playback.play(
                        queue = first,
                        startIndex = 0,
                        tail = { already -> playlists.tracks(playlistId, offset = already, limit = TrackPage) },
                        source = source,
                        sourceLabel = name,
                    )
                }
            }
        }
    }

    /** Enough to show the card if the album's own row cannot be read. */
    private fun Track.asAlbum() = Album(id = albumId, title = album, artist = artist, artwork = artwork, trackCount = 1)

    private data class Shelf(val items: List<ShelfItem>, val more: Boolean)

    private data class Featured(val settled: Boolean, val item: ShelfItem?)

    companion object {
        /** A few screens' worth. Small enough to read in a blink, large enough that a scroll rarely catches up. */
        private const val TrackPage = 60
        private const val AlbumPage = 24

        /** The least time the reload skeleton is shown. */
        private const val ReloadShownMs = 900L

        fun factory(
            library: LibraryRepository,
            playlists: PlaylistRepository,
            playback: PlaybackController,
        ): ViewModelProvider.Factory =
            viewModelFactory { initializer { HomeViewModel(library, playlists, playback) } }
    }
}
