package com.mellow.core.data.paging

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The part of a long list that has been read so far.
 *
 * @property loaded false only before the first page has arrived.
 * @property complete true once the end of the list has been reached.
 */
data class Paged<T>(
    val items: List<T> = emptyList(),
    val loaded: Boolean = false,
    val complete: Boolean = false,
)

/**
 * Reads a long list a page at a time, on demand.
 *
 * Nothing is read until [refresh] is called. After that the list grows only when
 * [loadMore] is, which a screen does as the user nears the end of what is showing. A
 * library of thousands of tracks therefore costs one small query to open, and the rest
 * are only ever paid for by scrolling to them.
 *
 * Use from the main thread; [fetch] is expected to do its own work off it.
 *
 * @param fetch returns up to `limit` items starting at `offset`. Fewer than asked for
 *   means the list ends there.
 * @param key what makes an item itself. The list can change underneath between pages, so
 *   a page may repeat something already read; repeats are dropped.
 */
class Pager<T>(
    private val scope: CoroutineScope,
    private val pageSize: Int,
    private val key: (T) -> Any,
    private val fetch: suspend (offset: Int, limit: Int) -> List<T>,
) {
    private val _state = MutableStateFlow(Paged<T>())
    val state: StateFlow<Paged<T>> = _state.asStateFlow()

    private var job: Job? = null

    /** Reads the next page, unless one is already on its way or the list has ended. */
    fun loadMore() {
        val current = _state.value
        if (!current.loaded || current.complete || job?.isActive == true) return
        job = scope.launch {
            val page = fetch(current.items.size, pageSize)
            val known = current.items.mapTo(HashSet(), key)
            _state.value = current.copy(
                items = current.items + page.filter { known.add(key(it)) },
                complete = page.size < pageSize,
            )
        }
    }

    /**
     * Reads the list again from the top. What is showing stays until the new copy is
     * ready, and as much is read as was already loaded, so a list the user has scrolled
     * down does not jump back or flash empty when a file changes.
     */
    fun refresh() {
        job?.cancel()
        job = scope.launch {
            val wanted = maxOf(pageSize, _state.value.items.size)
            val items = fetch(0, wanted).distinctBy(key)
            _state.value = Paged(items = items, loaded = true, complete = items.size < wanted)
        }
    }

    /** Forgets everything, as if nothing had been read. */
    fun clear() {
        job?.cancel()
        _state.value = Paged()
    }
}
