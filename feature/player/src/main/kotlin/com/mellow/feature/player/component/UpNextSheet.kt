package com.mellow.feature.player.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.mellow.core.designsystem.component.CoverArt
import com.mellow.core.designsystem.component.GlassSheet
import com.mellow.core.designsystem.component.Icon
import com.mellow.core.designsystem.component.Text
import com.mellow.core.designsystem.component.pressable
import com.mellow.core.designsystem.icon.MellowIcons
import com.mellow.core.designsystem.theme.ContinuousCornerShape
import com.mellow.core.designsystem.theme.MellowMotion
import com.mellow.core.designsystem.theme.MellowTheme
import com.mellow.core.playback.QueuedTrack
import com.mellow.core.playback.UpNext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What plays after the song that is on, in a sheet of glass over the player.
 *
 * It has two parts. First the user's own queue: the songs they asked for, in the order
 * they will play. That part is theirs to arrange: a row is moved by the handle at its end
 * and taken out by pushing it off to the side. Then, under its own heading, the rest of
 * the album or playlist that was put on, which carries on once the queue is through. That
 * part is only shown: reordering an album one is in the middle of confuses more than it helps.
 *
 * A tap on any row plays it now. The queue is not lost by that; what was still waiting
 * waits behind the song that was tapped.
 *
 * Call it from the content layer of a `GlassScene` whose backdrop is the player.
 *
 * @param onMove a row of the queue was dropped somewhere else: its old and new positions, from 0.
 */
@Composable
internal fun BoxScope.UpNextSheet(
    visible: Boolean,
    upNext: UpNext,
    onDismiss: () -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (key: String) -> Unit,
    onClear: () -> Unit,
    onPlay: (key: String) -> Unit,
) {
    GlassSheet(visible = visible, onDismiss = onDismiss) {
        Text("Up next", style = MellowTheme.type.title, modifier = Modifier.semantics { heading() })
        if (upNext.queued.isEmpty() && upNext.then.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("Nothing is lined up after this song.", color = MellowTheme.colors.inkMuted)
            Spacer(Modifier.height(10.dp))
            Text(HowToQueue, style = MellowTheme.type.caption, color = MellowTheme.colors.inkMuted)
        } else {
            Spacer(Modifier.height(4.dp))
            Lineup(upNext, onMove, onRemove, onClear, onPlay)
        }
    }
}

@Composable
private fun Lineup(
    upNext: UpNext,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (key: String) -> Unit,
    onClear: () -> Unit,
    onPlay: (key: String) -> Unit,
) {
    val colors = MellowTheme.colors
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val rowHeight = with(LocalDensity.current) { RowHeight.toPx() }
    val hand = remember { Hand() }
    // The queue as it is on screen while a row is in the hand. The player's own list is
    // only told when the row is put down, so that it is told once.
    val arranged = remember { mutableStateListOf<QueuedTrack>() }
    // Where the row just dropped still has to slide to reach its slot.
    val settle = remember { Animatable(0f) }

    // The player's word on the queue is what is shown, except between a row being picked
    // up and the player having heard where it was put down.
    val queued: List<QueuedTrack> = if (hand.key != null || hand.awaiting) arranged else upNext.queued
    LaunchedEffect(upNext.queued) { hand.awaiting = false }
    LaunchedEffect(hand.drops) {
        // If the player never answers (the move was not one it could make), stop waiting.
        if (hand.awaiting) {
            delay(AnswerMs)
            hand.awaiting = false
        }
    }

    fun pickUp(key: String, index: Int) {
        arranged.clear()
        arranged.addAll(upNext.queued)
        hand.from = index
        hand.at = index
        hand.travel = 0f
        hand.key = key
    }

    fun carry(delta: Float) {
        if (hand.key == null) return
        hand.travel += delta
        // Near the top or bottom of what can be seen, the list moves under the row.
        val layout = listState.layoutInfo
        layout.visibleItemsInfo.firstOrNull { it.key == hand.key }?.let { row ->
            val top = row.offset + hand.travel - (hand.at - hand.from) * rowHeight
            val bottom = top + row.size
            val over = when {
                bottom > layout.viewportEndOffset -> bottom - layout.viewportEndOffset
                top < layout.viewportStartOffset -> top - layout.viewportStartOffset
                else -> 0f
            }
            if (over != 0f) hand.travel += listState.dispatchRawDelta(over)
        }
        // Half way over a neighbour, the two change places.
        val target = (hand.from + (hand.travel / rowHeight).roundToInt()).coerceIn(0, arranged.lastIndex)
        if (target != hand.at) {
            arranged.add(target, arranged.removeAt(hand.at))
            hand.at = target
        }
    }

    fun putDown() {
        val key = hand.key ?: return
        val from = hand.from
        val to = hand.at
        val left = hand.travel - (to - from) * rowHeight
        hand.settling = key
        hand.key = null
        if (from != to) {
            hand.awaiting = true
            hand.drops++
            onMove(from, to)
        }
        scope.launch {
            settle.snapTo(left)
            settle.animateTo(0f, MellowMotion.settle())
            hand.settling = null
        }
    }

    // As tall as it needs to be, up to a little over half the screen; past that it scrolls.
    val tallest = (LocalConfiguration.current.screenHeightDp * 0.56f).dp
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = tallest)) {
        if (queued.isNotEmpty()) {
            item(key = "queue-heading") { Heading("Your queue", action = "Clear", onAction = onClear) }
            itemsIndexed(queued, key = { _, entry -> entry.key }) { index, entry ->
                val held = entry.key == hand.key
                val lift by animateFloatAsState(if (held) 1f else 0f, MellowMotion.press(), label = "queueLift")
                QueueRow(
                    entry = entry,
                    onClick = { onPlay(entry.key) },
                    onRemove = { onRemove(entry.key) },
                    modifier = Modifier
                        // A row in the hand goes where the finger does. The others slide out of its way.
                        .then(if (held || entry.key == hand.settling) Modifier else Modifier.animateItem())
                        .zIndex(if (held || entry.key == hand.settling) 1f else 0f)
                        .graphicsLayer {
                            translationY = when (entry.key) {
                                hand.key -> hand.travel - (hand.at - hand.from) * rowHeight
                                hand.settling -> settle.value
                                else -> 0f
                            }
                            val scale = 1f + 0.02f * lift
                            scaleX = scale
                            scaleY = scale
                        }
                        .drawBehind {
                            // Lifted: a soft plate under it, so it reads as above its neighbours.
                            if (lift > 0f) drawRoundRect(colors.wash, cornerRadius = CornerRadius(16.dp.toPx()), alpha = lift.coerceIn(0f, 1f))
                        },
                    handle = {
                        Box(
                            modifier = Modifier
                                .size(HandleTarget)
                                .draggable(
                                    state = rememberDraggableState(::carry),
                                    orientation = Orientation.Vertical,
                                    // No waiting to see whether it is a scroll: the handle is only for this.
                                    startDragImmediately = true,
                                    onDragStarted = { pickUp(entry.key, index) },
                                    onDragStopped = { putDown() },
                                )
                                .semantics {
                                    customActions = buildList {
                                        if (index > 0) add(CustomAccessibilityAction("Move up") { onMove(index, index - 1); true })
                                        if (index < queued.lastIndex) add(CustomAccessibilityAction("Move down") { onMove(index, index + 1); true })
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(MellowIcons.DragHandle, contentDescription = "Move", tint = if (held) colors.accent else colors.inkFaint, size = 22.dp)
                        }
                    },
                )
            }
        } else {
            item(key = "how") {
                Text(HowToQueue, style = MellowTheme.type.caption, color = colors.inkMuted, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
            }
        }
        if (upNext.then.isNotEmpty()) {
            item(key = "then-heading") {
                Heading(if (upNext.from != null) "Then from ${upNext.from}" else "Then")
            }
            items(upNext.then, key = { it.key }) { entry ->
                QueueRow(entry = entry, onClick = { onPlay(entry.key) }, onRemove = null, modifier = Modifier.animateItem())
            }
            if (upNext.more > 0) {
                item(key = "more") {
                    Text(
                        text = if (upNext.more == 1) "and 1 more" else "and ${upNext.more} more",
                        style = MellowTheme.type.caption,
                        color = colors.inkFaint,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                    )
                }
            }
        }
    }
}

/** The row that is being moved, and where it has got to. */
@Stable
private class Hand {
    /** The row in the hand. Null when none is. */
    var key: String? by mutableStateOf(null)

    /** The row that was just put down and is sliding into its slot. */
    var settling: String? by mutableStateOf(null)

    /** Where the row was picked up, and where in the list it is now. */
    var from = 0
    var at by mutableIntStateOf(0)

    /** How far the finger has taken it, in pixels. */
    var travel by mutableFloatStateOf(0f)

    /** True from a row being put down somewhere new until the player says the queue has changed. */
    var awaiting by mutableStateOf(false)

    /** Counts the rows put down, so that each one starts its own wait. */
    var drops by mutableIntStateOf(0)
}

/** The name of a part of the line-up, with at most one thing to do about the whole part. */
@Composable
private fun Heading(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            style = MellowTheme.type.callout,
            color = MellowTheme.colors.inkMuted,
            maxLines = 1,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (action != null) {
            Text(
                text = action,
                style = MellowTheme.type.callout,
                color = MellowTheme.colors.accent,
                modifier = Modifier.pressable(onClick = onAction, onClickLabel = "$action $text", pressedScale = 0.94f).padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
            )
        }
    }
}

/**
 * One song in the line-up: its cover, its name, who it is by.
 *
 * @param onRemove when given, the row can be pushed off to either side to take it out.
 * @param handle what goes at the far end: the grip a queued row is moved by.
 */
@Composable
private fun QueueRow(
    entry: QueuedTrack,
    onClick: () -> Unit,
    onRemove: (() -> Unit)?,
    modifier: Modifier = Modifier,
    handle: (@Composable () -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val thumb = remember { ContinuousCornerShape(CoverRadius) }
    val aside = remember { Animatable(0f) }
    var width by remember { mutableFloatStateOf(1f) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(RowHeight)
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .then(
                if (onRemove == null) {
                    Modifier
                } else {
                    Modifier
                        .draggable(
                            state = rememberDraggableState { delta -> scope.launch { aside.snapTo(aside.value + delta) } },
                            orientation = Orientation.Horizontal,
                            onDragStopped = { velocity ->
                                val gone = abs(aside.value) > width * RemoveAt || abs(velocity) > FlickVelocity
                                if (gone) {
                                    aside.animateTo(if (aside.value + velocity * 0.1f < 0f) -width else width, MellowMotion.glide(), velocity)
                                    onRemove()
                                } else {
                                    aside.animateTo(0f, MellowMotion.glide(), velocity)
                                }
                            },
                        )
                        .graphicsLayer {
                            translationX = aside.value
                            // It thins out as it goes, which says where it is going.
                            alpha = 1f - (abs(aside.value) / width).coerceIn(0f, 1f) * 0.8f
                        }
                        .semantics { customActions = listOf(CustomAccessibilityAction("Remove from queue") { onRemove(); true }) }
                },
            )
            .pressable(onClick = onClick, onClickLabel = "Play now", pressedScale = 0.98f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(entry.track.artwork.uri, Modifier.size(Cover), shape = thumb)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.track.title, style = MellowTheme.type.headline, maxLines = 1)
            Text(entry.track.artist, style = MellowTheme.type.caption, color = MellowTheme.colors.inkMuted, maxLines = 1)
        }
        handle?.invoke()
    }
}

private const val HowToQueue = "Press and hold a song anywhere, then choose Play next or Queue. It will wait here."

private val RowHeight = 60.dp
private val Cover = 44.dp
private val CoverRadius = 12.dp
private val HandleTarget = 48.dp

/** Pushed further aside than this much of its width, a row is taken out. */
private const val RemoveAt = 0.38f
private const val FlickVelocity = 1200f

/** How long to go on showing a new arrangement the player has not confirmed. */
private const val AnswerMs = 700L
