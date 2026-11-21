package com.mellow.core.playback.service

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.mellow.core.playback.R

/**
 * Plays audio independently of the UI.
 *
 * Media3 promotes this to a foreground service while something is playing, which is what
 * keeps music going with the screen off or the app swiped to the background, and gives the
 * system its lock-screen and notification controls. The app's screens never touch the
 * player here; they talk to it through a `MediaController`.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this, MeteredRenderersFactory(this))
            // Music: duck or pause for calls and other apps, and resume afterwards.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // Pause when headphones are unplugged rather than blaring from the speaker.
            .setHandleAudioBecomingNoisy(true)
            // Keep the CPU awake while playing with the screen off.
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        // The spectrum keeps time with the speaker, and has to know when it falls silent.
        AudioSpectrum.setPlaying(false)
        player.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) = AudioSpectrum.setPlaying(isPlaying)
            },
        )

        session = MediaSession.Builder(this, player)
            .setCallback(DeviceAudioOnly())
            .setBitmapLoader(CacheBitmapLoader(MarkWhenNoCover(this, DataSourceBitmapLoader(this))))
            .apply { launchApp()?.let(::setSessionActivity) }
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_stat_mellow)
            },
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping the app away stops the service unless music is actually playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_ENDED
        ) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /** Tapping the notification reopens the app. */
    private fun launchApp(): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/**
 * Resolves what a controller asks to play.
 *
 * A media item loses its playable URI when it crosses from a controller to the session,
 * so the controller sends it as request metadata and it is restored here. Because the
 * session is exported (the system's media controls need that), this is also the gate: only
 * audio in the phone's own media library is accepted, whoever is asking. Nothing on the
 * network, and no other app's files.
 */
@OptIn(UnstableApi::class)
private class DeviceAudioOnly : MediaSession.Callback {

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> {
        val playable = mediaItems.mapNotNull { item ->
            val uri = item.requestMetadata.mediaUri ?: return@mapNotNull null
            if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority != MediaStore.AUTHORITY) return@mapNotNull null
            item.buildUpon().setUri(uri).build()
        }
        return Futures.immediateFuture(playable)
    }
}

/**
 * Loads the picture for the notification and the lock screen. Plenty of music has no
 * cover; rather than leave the system's player card blank for those, it is given the
 * Mellow mark on the app's own pale green, so the card always looks like Mellow's.
 */
@OptIn(UnstableApi::class)
private class MarkWhenNoCover(context: Context, private val covers: BitmapLoader) : BitmapLoader {

    private val context = context.applicationContext

    /** Drawn once, the first time it is needed. The mark sits in the exact middle. */
    private val mark: Bitmap by lazy {
        Bitmap.createBitmap(MarkSide, MarkSide, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(Cream)
            val inset = (MarkSide * (1f - MarkShare) / 2f).toInt()
            ContextCompat.getDrawable(context, R.drawable.mellow_mark)?.apply {
                setBounds(inset, inset, MarkSide - inset, MarkSide - inset)
                draw(canvas)
            }
        }
    }

    override fun supportsMimeType(mimeType: String): Boolean = covers.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = covers.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        Futures.catching(covers.loadBitmap(uri), Exception::class.java, { mark }, MoreExecutors.directExecutor())

    private companion object {
        const val MarkSide = 512

        /** How much of the picture's width the mark takes. */
        const val MarkShare = 0.62f

        /** The palette's Cream. This module has no view of the design system's colours. */
        const val Cream = 0xFFE6F1E0.toInt()
    }
}

/** The stock renderers, with [AudioLevelMeter] listening in on the audio on its way out. */
@OptIn(UnstableApi::class)
private class MeteredRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .setAudioProcessors(arrayOf(TeeAudioProcessor(AudioLevelMeter)))
        .build()
}
