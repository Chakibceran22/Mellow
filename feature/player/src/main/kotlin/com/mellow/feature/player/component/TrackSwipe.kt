package com.mellow.feature.player.component

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import com.mellow.core.designsystem.theme.MellowMotion
import com.mellow.core.model.Track
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lets a [TrackSwipe] hear about skips that did not happen under a finger. A button that
 * skips says so here first, and the cover then leaves the same way a swipe would have
 * sent it. Anything it is not told about (a song ending, the notification) simply changes
 * the cover where it stands.
 */
@Stable
internal class TrackSwipeState {
    private var direction = 0
    private var askedAt = 0L

    /** The next track is about to be asked for. */
    fun expectNext() = expect(Onward)

    /** The previous track is about to be asked for. If the player restarts this one instead, nothing moves. */
    fun expectPrevious() = expect(Back)

    private fun expect(direction: Int) {
        this.direction = direction
        askedAt = SystemClock.uptimeMillis()
    }

    /** The direction asked for a moment ago, once; zero if none was, or it was too long ago to be the reason. */
    internal fun take(): Int {
        val taken = if (SystemClock.uptimeMillis() - askedAt < ExpectedFor) direction else 0
        direction = 0
        return taken
    }
}

/**
 * The part of the player that is the song (its cover and its name), made to be pushed
 * aside. Dragged to the left it goes off that way and the next track comes in from the
 * right; dragged to the right, the track before comes in from the left. A short flick
 * does the same as a long drag. Let go before half way and it comes back.
 *
 * Where there is nothing to go to (the end of the queue, or its start) it gives a little
 * under the finger and springs back, which says so without a word.
 *
 * [content] is always given the track to show, which is not always the one that is
 * playing: while a cover is on its way out it is still the old one, even though the
 * player has already moved on. Draw from that and nothing changes face mid-flight.
 *
 * Only sideways drags are taken. Up and down still belong to whatever holds this.
 *
 * @param track the track loaded in the player.
 * @param onNext ask the player for the next track.
 * @param onPrevious ask the player for the track before this one. It should mean that
 *   one, not "start this one again": a swipe back that leaves the same cover reads as broken.
 */
@Composable
internal fun TrackSwipe(
    track: Track,
    state: TrackSwipeState,
    canGoNext: Boolean,
    canGoPrevious: Boolean,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(Track) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // How far the song is from its place, in pixels. Read where it is drawn, nowhere else.
    val offset = remember { Animatable(0f) }
    var shown by remember { mutableStateOf(track) }
    var leaving by remember { mutableStateOf(false) }
    var width by remember { mutableFloatStateOf(1f) }
    val latest = rememberUpdatedState(track)
    val finger = remember { Finger() }

    /** Sends the song off to one side and brings back whatever is playing by then from the other. */
    fun carryOff(direction: Int, velocity: Float) {
        leaving = true
        scope.launch {
            val from = shown
            val away = direction * width * Away
            // Done as soon as it is out of sight, not when the spring has stopped trembling.
            offset.animateTo(away, spring(dampingRatio = 1f, stiffness = 700f, visibilityThreshold = width * 0.05f), velocity)
            // The player answers within a frame or two. If it has not yet, give it a moment.
            withTimeoutOrNull(ArrivalMs) { snapshotFlow { latest.value }.first { it.id != from.id } }
            val arrived = latest.value
            if (arrived.id != from.id) {
                shown = arrived
                offset.snapTo(-away)
            }
            // If nothing else came, the same song returns the way it went.
            leaving = false
            offset.animateTo(0f, MellowMotion.settle())
        }
    }

    LaunchedEffect(track) {
        when {
            // The same song, described anew: its name or cover was edited.
            track.id == shown.id -> shown = track
            // A swipe is already taking the old one out, and will bring this one in.
            leaving -> Unit
            else -> {
                val direction = state.take()
                if (direction == 0) shown = track else carryOff(direction, velocity = 0f)
            }
        }
    }

    val drag = rememberDraggableState { delta ->
        finger.travel += delta
        val wanted = finger.travel
        val open = if (wanted < 0f) canGoNext else canGoPrevious
        // Nothing that way: it gives a little, and no more.
        scope.launch { offset.snapTo(if (open) wanted else wanted * Give) }
    }

    Column(
        modifier = modifier
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .draggable(
                state = drag,
                orientation = Orientation.Horizontal,
                enabled = !leaving,
                onDragStarted = { finger.travel = offset.value },
                onDragStopped = { velocity ->
                    val at = offset.value
                    val far = width * CommitAt
                    val direction = when {
                        at < 0f && canGoNext && (at < -far || velocity < -FlickVelocity) -> Onward
                        at > 0f && canGoPrevious && (at > far || velocity > FlickVelocity) -> Back
                        else -> 0
                    }
                    if (direction == 0) {
                        scope.launch { offset.animateTo(0f, MellowMotion.glide(), velocity) }
                    } else {
                        // The music changes at once; the picture follows it out.
                        if (direction == Onward) onNext() else onPrevious()
                        carryOff(direction, velocity)
                    }
                },
            )
            // After the drag, so the place to put a finger stays where it was.
            .graphicsLayer { translationX = offset.value }
            .semantics {
                customActions = buildList {
                    if (canGoNext) add(CustomAccessibilityAction("Next track") { onNext(); true })
                    if (canGoPrevious) add(CustomAccessibilityAction("Previous track") { onPrevious(); true })
                }
            },
        content = { content(shown) },
    )
}

/** How far the finger has gone since it came down. Not state: nothing is drawn from it. */
private class Finger {
    var travel = 0f
}

/** Leaving to the left, which is what going on to the next track looks like. */
private const val Onward = -1

/** Leaving to the right: going back. */
private const val Back = 1

/** How far off, in widths of itself, the song is sent. Far enough to clear the screen, shadow and all. */
private const val Away = 1.2f

/** Dragged past this much of its width, letting go skips. */
private const val CommitAt = 0.3f

/** Faster than this, in pixels a second, a flick skips whatever the distance. */
private const val FlickVelocity = 900f

/** How much of the finger's travel the song follows when there is nothing in that direction. */
private const val Give = 0.18f

/** How long to wait for the player to say what is playing now before bringing the cover back. */
private const val ArrivalMs = 600L

/** A track change this soon after a button asked for one is taken to be that button's doing. */
private const val ExpectedFor = 900L
