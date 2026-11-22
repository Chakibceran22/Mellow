package com.mellow.core.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.mellow.core.model.Artwork
import com.mellow.core.model.RepeatMode
import com.mellow.core.model.Track
import com.mellow.core.playback.service.AudioLevelMeter
import com.mellow.core.playback.service.AudioSpectrum
import com.mellow.core.playback.service.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * [PlaybackController] backed by a Media3 `MediaController` bound to [PlaybackService].
 *
 * Connecting is asynchronous. Commands issued before the connection is up are queued and
 * run the moment it is, so callers never have to care whether the service has started.
 *
 * The player is the only record of what is queued. A track is packed into its media item
 * whole and unpacked again when it comes round to play, so nothing here keeps a second
 * copy of the queue that could fall out of step with the service.
 *
 * The player's list is kept in the order it will play. That one rule is what makes the
 * user's own queue simple: "play next" is an insertion after the current track, the queue
 * on screen is the list read from there on, and reordering it is moving an item. It is
 * also why shuffle is done here and not by the player. The player shuffles by walking its
 * list in a secret order, and drops anything added later at a random place in it, so a
 * song asked for next would play whenever it happened to come up. Here shuffle means
 * putting what is still to come in a random order, once, and leaving the user's queue at
 * the front of it; turning it off puts the list back in its own order around the track
 * that is on. Each item carries what is needed for that: where it stands in the list it
 * came from, or that the user queued it.
 */
class MediaPlaybackController(context: Context) : PlaybackController {

    private val context = context.applicationContext
    private var connecting: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = ArrayDeque<(MediaController) -> Unit>()

    /**
     * Shuffle and repeat are the user's settings, not the queue's: they are written down
     * each time they change and given back to the player when it starts, so a choice made
     * yesterday still holds today.
     */
    private val settings = this.context.getSharedPreferences("playback", Context.MODE_PRIVATE)

    /** Lives as long as the process, like the controller itself. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Where the rest of the current queue comes from. Null when the queue is already whole. */
    private var feed: Feed? = null

    /** Which list the current queue was started from. The player itself has no notion of this. */
    private var source: String? = null
    private var sourceLabel: String? = null

    /** The user's setting. The player's own shuffle is never switched on: see the class comment. */
    private var shuffle = settings.getBoolean(KeyShuffle, false)

    /**
     * How far the list that was put on has got: the place in it of the last of its tracks
     * to play. While one of the user's own songs is on, this is what remembers where the
     * list is to carry on from.
     */
    private var lastPlace = -1

    /** Gives each track the user queues a number of its own. Starts from the clock so that numbers from an earlier run are not handed out again. */
    private var lastQueued = System.currentTimeMillis()

    private val _upNext = MutableStateFlow(UpNext())
    override val upNext: StateFlow<UpNext> = _upNext.asStateFlow()

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    override val position: Flow<Long> = flow {
        while (true) {
            val player = controller
            emit(player?.currentPosition?.coerceAtLeast(0L) ?: 0L)
            delay(if (player?.isPlaying == true) PlayingPollMs else IdlePollMs)
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            // Something outside the app (a watch, a car) asked the player itself to
            // shuffle. It is given what it meant: shuffle, done the way it is done here.
            if (player.shuffleModeEnabled) {
                player.shuffleModeEnabled = false
                if (!shuffle) setShuffle(player, on = true)
            }
            publish(player)
            if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) publishUpNext(player)
            topUp(player, events)
        }
    }

    override fun audioLevel(): Float = AudioLevelMeter.current()

    override fun audioSpectrum(into: FloatArray): Boolean = AudioSpectrum.read(into)

    override fun connect() {
        if (controller != null || connecting != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) = onServiceGone()
            })
            .buildAsync()
        connecting = future
        future.addListener(
            {
                connecting = null
                val connected = runCatching { future.get() }.getOrNull() ?: return@addListener
                controller = connected
                connected.shuffleModeEnabled = false
                connected.repeatMode = settings.getInt(KeyRepeat, Player.REPEAT_MODE_OFF)
                connected.addListener(listener)
                publish(connected)
                publishUpNext(connected)
                while (pending.isNotEmpty()) pending.removeFirst().invoke(connected)
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    override fun play(queue: List<Track>, startIndex: Int?, tail: QueueTail?, source: String?, sourceLabel: String?) {
        if (queue.isEmpty()) return
        this.source = source
        this.sourceLabel = sourceLabel
        // A new queue: whatever was feeding the old one is done, even mid-request.
        feed = tail?.let { Feed(it, loaded = queue.size) }
        send { player ->
            lastPlace = -1
            // What the user queued themselves stays queued, whatever else is put on.
            val kept = queuedAhead(player)
            val items = queue.mapIndexed { place, track -> track.toMediaItem(place = place) }
            val start = startIndex?.coerceIn(0, queue.lastIndex) ?: if (shuffle) Random.nextInt(queue.size) else 0
            if (shuffle) {
                // The one it starts on, the user's queue, then everything else in a random order.
                val rest = items.filterIndexed { index, _ -> index != start }.shuffled()
                player.setMediaItems(listOf(items[start]) + kept + rest, 0, 0L)
            } else {
                player.setMediaItems(items.subList(0, start + 1) + kept + items.subList(start + 1, items.size), start, 0L)
            }
            player.prepare()
            player.play()
        }
    }

    override fun playNext(tracks: List<Track>) = enqueue(tracks, atFront = true)

    override fun addToQueue(tracks: List<Track>) = enqueue(tracks, atFront = false)

    private fun enqueue(tracks: List<Track>, atFront: Boolean) {
        val wanted = tracks.distinctBy(Track::id)
        if (wanted.isEmpty()) return
        send { player ->
            if (player.mediaItemCount == 0 || player.currentMediaItem == null) {
                // Nothing is on, so the queue is the music: the first starts and the rest wait behind it.
                source = QueueSource.Queue
                sourceLabel = null
                feed = null
                player.setMediaItems(wanted.map { it.toMediaItem(queued = ++lastQueued) }, 0, 0L)
                player.prepare()
                player.play()
                return@send
            }
            // The track that is on cannot follow itself.
            val current = player.currentMediaItem?.mediaId
            val joining = wanted.filter { it.id != current }
            if (joining.isEmpty()) return@send
            // One that is already coming up is moved, not played twice. Backwards, so
            // that taking one out does not shift the ones still to be looked at.
            val ids = joining.mapTo(HashSet(), Track::id)
            for (index in player.mediaItemCount - 1 downTo player.currentMediaItemIndex + 1) {
                if (player.getMediaItemAt(index).mediaId in ids) player.removeMediaItem(index)
            }
            val first = player.currentMediaItemIndex + 1
            val at = if (atFront) first else first + queuedAhead(player).size
            player.addMediaItems(at, joining.map { it.toMediaItem(queued = ++lastQueued) })
            // It had played to the end of everything and stopped: what was just queued is what it plays now.
            if (player.playbackState == Player.STATE_ENDED) {
                player.seekToDefaultPosition(first)
                player.play()
            }
        }
    }

    override fun moveQueued(from: Int, to: Int) = send { player ->
        val first = player.currentMediaItemIndex + 1
        val count = queuedAhead(player).size
        if (first > 0 && from != to && from in 0 until count && to in 0 until count) player.moveMediaItem(first + from, first + to)
    }

    override fun removeQueued(key: String) = send { player ->
        val index = indexOf(player, key)
        if (index > player.currentMediaItemIndex) player.removeMediaItem(index)
    }

    override fun clearQueued() = send { player ->
        val first = player.currentMediaItemIndex + 1
        val count = queuedAhead(player).size
        if (first > 0 && count > 0) player.removeMediaItems(first, first + count)
    }

    override fun skipTo(key: String) = send { player ->
        val target = indexOf(player, key)
        val current = player.currentMediaItemIndex
        if (target <= current) return@send
        val first = current + 1
        val count = queuedAhead(player).size
        if (target < first + count) {
            // One of the user's queue: it comes to the front, and the others keep waiting behind it.
            if (target != first) player.moveMediaItem(target, first)
            player.seekToDefaultPosition(first)
        } else {
            // One from further on. The user's queue is carried along, to play after it.
            if (count > 0) player.moveMediaItems(first, first + count, target - count + 1)
            player.seekToDefaultPosition(target - count)
        }
        player.play()
    }

    /** The user's own queue: every item straight after the current one that they queued, as far as those run. */
    private fun queuedAhead(player: Player): List<MediaItem> {
        val current = player.currentMediaItemIndex
        if (current < 0) return emptyList()
        val queued = ArrayList<MediaItem>()
        for (index in current + 1 until player.mediaItemCount) {
            val item = player.getMediaItemAt(index)
            if (item.queued() == 0L) break
            queued += item
        }
        return queued
    }

    /** Where in the player's list the item [key] names is now, or -1. */
    private fun indexOf(player: Player, key: String): Int {
        for (index in player.currentMediaItemIndex + 1 until player.mediaItemCount) {
            if (player.getMediaItemAt(index).key() == key) return index
        }
        return -1
    }

    /**
     * Turns shuffle on or off by putting what is still to come in order: a random one, or
     * the list's own. The track that is on is never touched, so the music does not stop,
     * and the user's queue stays where it is, straight after it.
     */
    private fun setShuffle(player: Player, on: Boolean) {
        shuffle = on
        settings.edit().putBoolean(KeyShuffle, on).apply()
        val current = player.currentMediaItemIndex
        val count = player.mediaItemCount
        if (current < 0 || count == 0) return
        val all = List(count) { player.getMediaItemAt(it) }
        val queuedCount = queuedAhead(player).size
        val playing = all[current]
        // Every track of the list that was put on, bar the one playing: the ones already
        // heard as much as the ones to come. Tracks the user queued and has heard are let go.
        val others = all.filterIndexed { index, item -> index != current && item.queued() == 0L }

        if (on) {
            // All of them are to come, in a random order.
            player.replaceMediaItems(current + 1 + queuedCount, count, others.shuffled())
            if (current > 0) player.removeMediaItems(0, current)
        } else {
            // Back around the track that is on: what stands before it in the list goes
            // before it, what stands after goes after. If one of the user's own songs
            // is on, the list carries on from the last of its tracks that played.
            val place = if (playing.queued() == 0L) playing.place() else lastPlace + 1
            val sorted = others.sortedBy { it.place() }
            player.replaceMediaItems(current + 1 + queuedCount, count, sorted.filter { it.place() >= place })
            player.replaceMediaItems(0, current, sorted.filter { it.place() < place })
        }
    }

    override fun removeFromQueue(trackIds: Set<String>) {
        if (trackIds.isEmpty()) return
        send { player ->
            // Backwards, so removing one does not shift the ones still to be checked.
            for (index in player.mediaItemCount - 1 downTo 0) {
                if (player.getMediaItemAt(index).mediaId in trackIds) player.removeMediaItem(index)
            }
        }
    }

    override fun refresh(tracks: Collection<Track>) {
        if (tracks.isEmpty()) return
        val changed = tracks.associateBy(Track::id)
        send { player ->
            for (index in 0 until player.mediaItemCount) {
                val item = player.getMediaItemAt(index)
                val track = changed[item.mediaId] ?: continue
                // Same file, new description: the player swaps the item without stopping it.
                player.replaceMediaItem(index, track.toMediaItem(place = item.place(), queued = item.queued()))
            }
            // What is published was unpacked from the old item; unpack it again.
            _state.value = _state.value.copy(track = _state.value.track?.let { changed[it.id] ?: it })
            publishUpNext(player)
        }
    }

    /** True between [letGo] and [pickUp], if the player really did have to let go. */
    private var lettingGo = false

    override fun letGo(trackIds: Set<String>) {
        val player = controller ?: return
        if (player.mediaItemCount == 0 || player.playbackState == Player.STATE_IDLE) return
        val current = player.currentMediaItem?.mediaId
        val next = if (player.hasNextMediaItem()) player.getMediaItemAt(player.nextMediaItemIndex).mediaId else null
        if (current !in trackIds && next !in trackIds) return
        lettingGo = true
        // Releases what is loaded and keeps the queue, the position and whether it was playing.
        player.stop()
    }

    override fun pickUp() {
        if (!lettingGo) return
        lettingGo = false
        controller?.prepare()
    }

    override fun togglePlayPause() = send { player ->
        when {
            player.isPlaying -> player.pause()
            // Finished the queue: the same button starts it over.
            player.playbackState == Player.STATE_ENDED -> {
                player.seekToDefaultPosition(0)
                player.play()
            }
            else -> player.play()
        }
    }

    override fun next() = send { it.seekToNextMediaItem() }

    /** Restarts the track if it is under way, otherwise steps back one, as music players do. */
    override fun previous() = send { it.seekToPrevious() }

    override fun previousTrack() = send { it.seekToPreviousMediaItem() }

    override fun seekTo(positionMs: Long) = send { it.seekTo(positionMs) }

    override fun toggleShuffle() = send { player ->
        setShuffle(player, on = !shuffle)
        publish(player)
    }

    override fun cycleRepeat() = send { player ->
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    private fun send(command: (MediaController) -> Unit) {
        val player = controller
        if (player != null) {
            command(player)
        } else {
            pending.addLast(command)
            connect()
        }
    }

    /**
     * Asks the tail for the next page when the queue is about to run dry.
     *
     * In order, that is when the last few tracks are reached. In shuffle the tracks not
     * read yet ought to have their chance of coming up soon, so there is no waiting for
     * the end: each track that starts pulls one more page in, mixed into what is to come.
     */
    private fun topUp(player: Player, events: Player.Events) {
        val feed = feed ?: return
        if (feed.busy) return

        val moved = events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
        val wanted = if (shuffle) {
            moved
        } else {
            val left = player.mediaItemCount - 1 - player.currentMediaItemIndex
            left <= TopUpWithin && (moved || events.contains(Player.EVENT_TIMELINE_CHANGED))
        }
        if (!wanted) return

        feed.busy = true
        scope.launch {
            val more = runCatching { feed.tail(feed.loaded) }.getOrDefault(emptyList())
            // The queue was replaced while this page was being read: it belongs to nobody.
            if (this@MediaPlaybackController.feed !== feed) return@launch
            feed.busy = false
            if (more.isEmpty()) {
                this@MediaPlaybackController.feed = null
            } else {
                val items = more.mapIndexed { index, track -> track.toMediaItem(place = feed.loaded + index) }
                feed.loaded += more.size
                val live = controller ?: return@launch
                if (shuffle) mixIn(live, items) else live.addMediaItems(items)
            }
        }
    }

    /**
     * Shuffles newly read tracks in among those still to come. The next few are left as
     * they are: they are what the user can see is coming, and the very next one may
     * already be loaded.
     */
    private fun mixIn(player: Player, items: List<MediaItem>) {
        val count = player.mediaItemCount
        val from = (player.currentMediaItemIndex + 1 + queuedAhead(player).size + SettledAhead).coerceAtMost(count)
        val pool = List(count - from) { player.getMediaItemAt(from + it) } + items
        player.replaceMediaItems(from, count, pool.shuffled())
    }

    private fun publish(player: Player) {
        if (player.repeatMode != settings.getInt(KeyRepeat, Player.REPEAT_MODE_OFF)) {
            settings.edit().putInt(KeyRepeat, player.repeatMode).apply()
        }

        val item = player.currentMediaItem
        if (item != null && item.queued() == 0L) lastPlace = item.place()
        val previous = _state.value.track
        _state.value = PlaybackState(
            // Unpacked once per track, not on every tick of the player.
            track = if (item == null) null else previous?.takeIf { it.id == item.mediaId } ?: item.toTrack(),
            isPlaying = player.isPlaying,
            durationMs = player.duration.takeIf { it > 0 } ?: 0L,
            shuffle = shuffle,
            repeat = when (player.repeatMode) {
                Player.REPEAT_MODE_ALL -> RepeatMode.All
                Player.REPEAT_MODE_ONE -> RepeatMode.One
                else -> RepeatMode.Off
            },
            source = if (item == null) null else source,
            hasNext = player.hasNextMediaItem(),
            hasPrevious = player.hasPreviousMediaItem(),
        )
    }

    /** Reads what is coming out of the player's list: the user's queue, then the first of the rest. */
    private fun publishUpNext(player: Player) {
        val current = player.currentMediaItemIndex
        val count = player.mediaItemCount
        if (current < 0 || count == 0) {
            _upNext.value = UpNext()
            return
        }
        val queued = ArrayList<QueuedTrack>()
        var index = current + 1
        while (index < count) {
            val item = player.getMediaItemAt(index)
            if (item.queued() == 0L) break
            item.toTrack()?.let { queued += QueuedTrack(item.key(), it) }
            index++
        }
        val then = ArrayList<QueuedTrack>()
        val shownUntil = minOf(count, index + ThenShown)
        for (later in index until shownUntil) {
            val item = player.getMediaItemAt(later)
            item.toTrack()?.let { then += QueuedTrack(item.key(), it) }
        }
        _upNext.value = UpNext(queued = queued, then = then, more = count - shownUntil, from = sourceLabel)
    }

    /** The service was stopped (for instance, the app was swiped away while paused). */
    private fun onServiceGone() {
        controller?.removeListener(listener)
        controller = null
        feed = null
        source = null
        sourceLabel = null
        lastPlace = -1
        _state.value = PlaybackState()
        _upNext.value = UpNext()
    }

    /** The number the user's queue gave this item, or 0 if it is part of the list that was put on. */
    private fun MediaItem.queued(): Long = mediaMetadata.extras?.getLong(ExtraQueued, 0L) ?: 0L

    /** Where this item stands in the list it came from. Items of no known place sort last. */
    private fun MediaItem.place(): Int = mediaMetadata.extras?.getInt(ExtraPlace, Int.MAX_VALUE) ?: Int.MAX_VALUE

    /** A name for this item's place in line: see [QueuedTrack.key]. */
    private fun MediaItem.key(): String = queued().let { if (it != 0L) "q$it" else "p${place()}:$mediaId" }

    /**
     * @param place where the track stands in the list being put on.
     * @param queued the number the user's queue gives it, when it is the user who queued it.
     */
    private fun Track.toMediaItem(place: Int = Int.MAX_VALUE, queued: Long = 0L): MediaItem {
        val audio = Uri.parse(audioUri)
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(audio)
            // The URI above does not survive the trip to the service; this copy does.
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(audio).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setArtworkUri(Uri.parse(artwork.uri))
                    .setExtras(
                        Bundle().apply {
                            putString(ExtraAlbumId, albumId)
                            putLong(ExtraDurationMs, durationMs)
                            putInt(ExtraPlace, place)
                            putLong(ExtraQueued, queued)
                        },
                    )
                    .build(),
            )
            .build()
    }

    /** The reverse of [toMediaItem]. Null for an item this app did not queue. */
    private fun MediaItem.toTrack(): Track? {
        val audio = requestMetadata.mediaUri ?: return null
        val extras = mediaMetadata.extras
        return Track(
            id = mediaId,
            title = mediaMetadata.title?.toString().orEmpty(),
            artist = mediaMetadata.artist?.toString().orEmpty(),
            album = mediaMetadata.albumTitle?.toString().orEmpty(),
            albumId = extras?.getString(ExtraAlbumId).orEmpty(),
            durationMs = extras?.getLong(ExtraDurationMs) ?: 0L,
            audioUri = audio.toString(),
            artwork = Artwork(uri = mediaMetadata.artworkUri?.toString().orEmpty()),
        )
    }

    /** A queue that is still being read, and how much of it has been. */
    private class Feed(val tail: QueueTail, var loaded: Int) {
        var busy = false
    }

    private companion object {
        const val PlayingPollMs = 60L
        const val IdlePollMs = 250L

        /** Ask for more when this many tracks or fewer are left after the current one. */
        const val TopUpWithin = 3

        const val KeyShuffle = "shuffle"
        const val KeyRepeat = "repeat"

        const val ExtraAlbumId = "mellow.albumId"
        const val ExtraDurationMs = "mellow.durationMs"
        const val ExtraPlace = "mellow.place"
        const val ExtraQueued = "mellow.queued"

        /** How many of the tracks after the user's queue are listed as coming up. */
        const val ThenShown = 30

        /** In shuffle, how many of the tracks to come are left alone when more are mixed in. */
        const val SettledAhead = 6
    }
}
