package com.mellow.core.model

/** What a search of the library found, best matches first within each list. */
data class SearchResults(
    val tracks: List<Track>,
    val albums: List<Album>,
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && albums.isEmpty()

    companion object {
        val None = SearchResults(tracks = emptyList(), albums = emptyList())
    }
}
