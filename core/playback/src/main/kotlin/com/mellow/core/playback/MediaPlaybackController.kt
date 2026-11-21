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

/**
 * [PlaybackController] backed by a Media3 `MediaController` bound to [PlaybackService].
 *
 * Connecting is asynchronous. Commands issued before the connection is up are queued and
 * run the moment it is, so callers never have to care whether the service has started.
 *
 * The player is the only record of what is queued. A track is packed into its media item
 * whole and unpacked again when it comes round to play, so nothing here keeps a second
 * copy of the queue that could fall out of step with the service.
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
            publish(player)
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
                connected.shuffleModeEnabled = settings.getBoolean(KeyShuffle, false)
                connected.repeatMode = settings.getInt(KeyRepeat, Player.REPEAT_MODE_OFF)
                connected.addListener(listener)
                publish(connected)
                while (pending.isNotEmpty()) pending.removeFirst().invoke(connected)
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    override fun play(queue: List<Track>, startIndex: Int?, tail: QueueTail?, source: String?) {
        if (queue.isEmpty()) return
        this.source = source
        // A new queue: whatever was feeding the old one is done, even mid-request.
        feed = tail?.let { Feed(it, loaded = queue.size) }
        send { player ->
            val items = queue.map { it.toMediaItem() }
            if (startIndex == null) {
                // The player picks: first in order, or first in its shuffled order.
                player.setMediaItems(items, /* resetPosition = */ true)
            } else {
                player.setMediaItems(items, startIndex.coerceIn(0, queue.lastIndex), 0L)
            }
            player.prepare()
            player.play()
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
                val track = changed[player.getMediaItemAt(index).mediaId] ?: continue
                // Same file, new description: the player swaps the item without stopping it.
                player.replaceMediaItem(index, track.toMediaItem())
            }
            // What is published was unpacked from the old item; unpack it again.
            _state.value = _state.value.copy(track = _state.value.track?.let { changed[it.id] ?: it })
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

    override fun toggleShuffle() = send { it.shuffleModeEnabled = !it.shuffleModeEnabled }

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
     * In order, that is when the last few tracks are reached. In shuffle the next track
     * can be anywhere, so there is no "near the end": instead each track that starts pulls
     * one more page into the pool, which keeps widening for as long as the music plays.
     */
    private fun topUp(player: Player, events: Player.Events) {
        val feed = feed ?: return
        if (feed.busy) return

        val moved = events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)
        val wanted = if (player.shuffleModeEnabled) {
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
                feed.loaded += more.size
                controller?.addMediaItems(more.map { it.toMediaItem() })
            }
        }
    }

    private fun publish(player: Player) {
        if (player.shuffleModeEnabled != settings.getBoolean(KeyShuffle, false) ||
            player.repeatMode != settings.getInt(KeyRepeat, Player.REPEAT_MODE_OFF)
        ) {
            settings.edit().putBoolean(KeyShuffle, player.shuffleModeEnabled).putInt(KeyRepeat, player.repeatMode).apply()
        }

        val item = player.currentMediaItem
        val previous = _state.value.track
        _state.value = PlaybackState(
            // Unpacked once per track, not on every tick of the player.
            track = if (item == null) null else previous?.takeIf { it.id == item.mediaId } ?: item.toTrack(),
            isPlaying = player.isPlaying,
            durationMs = player.duration.takeIf { it > 0 } ?: 0L,
            shuffle = player.shuffleModeEnabled,
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

    /** The service was stopped (for instance, the app was swiped away while paused). */
    private fun onServiceGone() {
        controller?.removeListener(listener)
        controller = null
        feed = null
        source = null
        _state.value = PlaybackState()
    }

    private fun Track.toMediaItem(): MediaItem {
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
    }
}
