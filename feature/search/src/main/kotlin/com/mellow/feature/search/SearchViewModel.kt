package com.mellow.feature.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mellow.core.data.LibraryRepository
import com.mellow.core.model.SearchResults
import com.mellow.core.playback.PlaybackController
import com.mellow.core.playback.QueueSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

class SearchViewModel(
    private val library: LibraryRepository,
    private val playback: PlaybackController,
) : ViewModel() {

    /**
     * The text in the search field.
     *
     * Compose state rather than a flow: a text field must get its own text back in the
     * same frame it was typed, or the keyboard and the field drift apart and fast typing
     * drops letters. A flow collected by the UI arrives a frame late.
     */
    var query by mutableStateOf("")
        private set

    /** The answer on screen, if any. Outlives a restart of [steps], so coming back to the app does not blank the page. */
    private var shown: Step.Answer? = null

    private var albumJob: Job? = null

    /**
     * Each change to the text, to the library or to the permission starts this over, and
     * starting over abandons whatever search was still running.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val steps: Flow<Step> =
        combine(
            snapshotFlow { query.trim() },
            library.access,
            library.revision,
        ) { text, access, _ -> if (access) text else null }
            .transformLatest<String?, Step> { text ->
                when {
                    text == null -> {
                        shown = null
                        emit(Step.NoAccess)
                    }
                    text.isEmpty() -> {
                        shown = null
                        emit(Step.Idle)
                    }
                    else -> {
                        // With an answer already up, it stays up until the next one lands:
                        // results change in one step instead of flashing to placeholders
                        // between keystrokes.
                        if (shown == null) emit(Step.Waiting)
                        // Wait for a pause in the typing before asking.
                        delay(DebounceMs)
                        val answer = Step.Answer(text, library.search(text))
                        shown = answer
                        emit(answer)
                    }
                }
            }

    val uiState: StateFlow<SearchUiState> =
        combine(steps, playback.state) { step, playback ->
            when (step) {
                Step.NoAccess -> SearchUiState.NoAccess
                Step.Idle -> SearchUiState.Idle
                Step.Waiting -> SearchUiState.Searching
                is Step.Answer ->
                    if (step.results.isEmpty) {
                        SearchUiState.NothingFound(step.query)
                    } else {
                        SearchUiState.Results(
                            query = step.query,
                            albums = step.results.albums,
                            tracks = step.results.tracks,
                            currentTrackId = playback.trackId,
                            // The album that is playing as an album, if one is.
                            currentAlbumId = playback.source?.removePrefix("album:")?.takeIf { playback.source != it },
                            isPlaying = playback.isPlaying,
                        )
                    }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState.Idle)

    fun onQueryChange(text: String) {
        query = text
    }

    /**
     * Tapping the track that is already on pauses or resumes it. Any other starts it, with
     * the rest of the songs that were found queued up behind it.
     */
    fun onTrackClick(trackId: String) {
        if (playback.state.value.trackId == trackId) {
            playback.togglePlayPause()
            return
        }
        val tracks = (uiState.value as? SearchUiState.Results)?.tracks ?: return
        val index = tracks.indexOfFirst { it.id == trackId }
        if (index >= 0) playback.play(tracks, index, sourceLabel = "what you searched for")
    }

    /** Same rule for albums: toggle if one of its tracks is on, otherwise play it from the top. */
    fun onAlbumClick(albumId: String) {
        val source = QueueSource.album(albumId)
        if (playback.state.value.track != null && playback.state.value.source == source) {
            playback.togglePlayPause()
            return
        }
        // Its tracks are read only now that they are wanted. If another album is tapped
        // before they arrive, the later tap wins.
        albumJob?.cancel()
        albumJob = viewModelScope.launch {
            val tracks = library.albumTracks(albumId)
            if (tracks.isNotEmpty()) playback.play(tracks, source = source, sourceLabel = tracks.first().album)
        }
    }

    companion object {
        fun factory(library: LibraryRepository, playback: PlaybackController): ViewModelProvider.Factory =
            viewModelFactory { initializer { SearchViewModel(library, playback) } }
    }
}

/** Where a search has got to. */
private sealed interface Step {
    data object NoAccess : Step
    data object Idle : Step
    data object Waiting : Step
    data class Answer(val query: String, val results: SearchResults) : Step
}

/** Long enough to skip the letters in the middle of a word, short enough to feel live. */
private const val DebounceMs = 250L
