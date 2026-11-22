package com.mellow.feature.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import android.util.Size
import com.mellow.core.playback.PlaybackController
import com.mellow.core.playback.PlaybackState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The application object implements this, so that the widgets' receivers (which the
 * system creates, and which are handed nothing but a context) can find [MellowWidgets].
 */
interface WidgetHost {
    val widgets: MellowWidgets
}

/**
 * Mellow's widgets on the home screen: what is playing, and buttons for it, without
 * opening the app.
 *
 * A widget is not a screen of this app. The launcher draws it, in its own process, from a
 * description this app sends; it stays on screen with that description whether the app is
 * running or not. So there are two jobs here and nothing else:
 *
 * - **Saying what is on.** Every time the song or its state changes, each widget is sent
 *   a new description ([WidgetRenderer]). Nothing is sent on a timer.
 * - **Doing what a tap asks.** A button on a widget wakes this app with a broadcast
 *   ([WidgetControls]), and the press is passed to the [PlaybackController] exactly as a
 *   press on the same button in the app would be.
 *
 * Both lean on the controller remembering what was on when the app was last open: it is
 * what a widget shows after the phone restarts, and what its play button starts.
 *
 * One of these is made, with the application. Call [start] once.
 */
class MellowWidgets(context: Context, private val playback: PlaybackController) {

    private val context = context.applicationContext
    private val manager: AppWidgetManager = AppWidgetManager.getInstance(this.context)
    private val renderer = WidgetRenderer(this.context)

    /** Lives as long as the process. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** One drawing at a time: the renderer's pictures are not shared, and the last description sent must be the newest. */
    private val drawing = Mutex()

    /** Begins keeping the widgets in step with what is playing. */
    fun start() {
        scope.launch {
            // Until this, "nothing is on" may only mean that nobody has looked yet.
            playback.recalled()
            playback.state.map(::shownOf).distinctUntilChanged().collectLatest { draw() }
        }
    }

    /**
     * Draws every widget again as things stand: one has been placed or resized, or the
     * phone has restarted and the launcher has nothing to show. [done] is called when
     * they have been sent.
     */
    internal fun redraw(done: () -> Unit) {
        scope.launch {
            try {
                playback.recalled()
                draw()
            } finally {
                done()
            }
        }
    }

    /**
     * Does what a tap on one of a widget's buttons asks. [done] is called once it has
     * taken effect, or it is clear that it will not.
     *
     * The app may have been started for this tap alone, with nothing running: the
     * controller then has to bring up the player and hand it the last session before the
     * press can land. The caller is kept waiting until the player answers, because the
     * system only promises to keep the app alive, and to let it take up playing in the
     * background, for as long as the tap is still being dealt with.
     */
    internal fun press(action: String, done: () -> Unit) {
        scope.launch {
            try {
                playback.recalled()
                val before = playback.state.value
                // Nothing to act on. The widget says as much, and its buttons are not shown.
                if (before.track == null) return@launch
                when (action) {
                    WidgetControls.Toggle -> {
                        playback.togglePlayPause()
                        if (!before.isPlaying) {
                            withTimeoutOrNull(StartsWithinMs) { playback.state.first { it.isPlaying } }
                            // Sound comes a moment before the phone has been told that this is music playing.
                            delay(SettlesWithinMs)
                        }
                    }
                    WidgetControls.Next -> {
                        if (!before.hasNext) return@launch
                        playback.next()
                        withTimeoutOrNull(MovesWithinMs) { playback.state.first { it.trackId != before.trackId } }
                    }
                    WidgetControls.Previous -> {
                        playback.previous()
                        // It may only start the song again, which changes nothing that can be waited for.
                        withTimeoutOrNull(MovesWithinMs) { playback.state.first { it.trackId != before.trackId } }
                    }
                }
            } finally {
                done()
            }
        }
    }

    /** Sends every widget a description of what is on at the moment it is its turn. */
    private suspend fun draw() = drawing.withLock {
        withContext(Dispatchers.IO) {
            try {
                for (kind in Kinds) {
                    for (id in manager.getAppWidgetIds(ComponentName(context, kind.provider))) {
                        ensureActive()
                        // Read now, not when this was asked for: a wait for the lock may have let the song change.
                        val views = renderer.render(shownOf(playback.state.value), manager.getAppWidgetOptions(id), kind.size)
                        manager.updateAppWidget(id, views)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // A launcher that refuses a description leaves the widget as it was. Nothing in the app depends on it.
                Log.w(Tag, "Could not draw the widgets", failure)
            }
        }
    }

    private fun shownOf(state: PlaybackState): Shown? = state.track?.let { track ->
        Shown(
            title = track.title,
            artist = track.artist,
            artworkUri = track.artwork.uri,
            isPlaying = state.isPlaying,
            hasNext = state.hasNext,
        )
    }

    /** One entry in the phone's list of widgets, and the size it has until the launcher says otherwise, in dp. */
    private class Kind(val provider: Class<*>, val size: Size)

    companion object {
        /**
         * The action of the intent a tap on a widget opens the app with, when it should
         * come up showing the player. The app's main screen looks for it.
         */
        const val ActionShowPlayer = "com.mellow.feature.widget.SHOW_PLAYER"

        private const val Tag = "MellowWidgets"

        private val Kinds = listOf(
            Kind(PlayerWidget::class.java, Size(300, 64)),
            Kind(CoverWidget::class.java, Size(150, 160)),
        )

        /** How long a cold start may take to get from a tap to sound. */
        private const val StartsWithinMs = 4_000L
        private const val SettlesWithinMs = 400L

        /** How long to wait for a skip to show. */
        private const val MovesWithinMs = 1_500L
    }
}
