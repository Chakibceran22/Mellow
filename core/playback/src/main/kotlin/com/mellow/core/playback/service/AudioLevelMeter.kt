package com.mellow.core.playback.service

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Measures how loud playback is by listening in on the PCM that the player sends to the
 * speaker. It works on whatever is playing, needs no microphone permission, and costs a
 * few hundred additions per audio buffer.
 *
 * Written from the audio thread, read from the main thread; both fields are single
 * volatile writes, so no lock is needed.
 */
@OptIn(UnstableApi::class)
internal object AudioLevelMeter : TeeAudioProcessor.AudioBufferSink {

    @Volatile private var level = 0f
    @Volatile private var measuredAt = 0L
    @Volatile private var encoding = C.ENCODING_INVALID

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        this.encoding = encoding
        AudioSpectrum.reset(sampleRateHz, channelCount)
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (encoding != C.ENCODING_PCM_16BIT) return
        // A view over the same bytes: reading through it leaves the player's buffer untouched.
        val samples = buffer.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        val count = samples.remaining()
        if (count == 0) return

        val stride = max(1, count / SamplesPerBuffer)
        var sum = 0.0
        var read = 0
        var index = 0
        while (index < count) {
            val sample = samples.get(index) / 32768.0
            sum += sample * sample
            read++
            index += stride
        }
        level = sqrt(sum / read).toFloat()
        measuredAt = SystemClock.elapsedRealtime()

        // The same samples, taken apart by pitch, for whoever is watching the spectrum.
        AudioSpectrum.listen(samples)
    }

    /** The latest level, or silence once buffers have stopped arriving (paused or ended). */
    fun current(): Float =
        if (SystemClock.elapsedRealtime() - measuredAt > StaleAfterMs) 0f else level

    private const val SamplesPerBuffer = 256
    private const val StaleAfterMs = 300L
}
