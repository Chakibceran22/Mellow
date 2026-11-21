package com.mellow.core.playback.service

import android.os.SystemClock
import com.mellow.core.playback.SpectrumBands
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Takes the sound apart into bands, from the lowest notes to the highest, for a display
 * that moves with the music.
 *
 * It listens in on the same PCM as [AudioLevelMeter], on the player's own thread, and
 * does two things with it.
 *
 * It measures. Every [Hop] samples it looks at the last [Window] of them, finds how much
 * of each pitch is in there (a Fourier transform), and adds the pitches up into [Bands]
 * bands that are each about a third of an octave wide, the way hearing spaces them.
 *
 * And it keeps time. What it is handed has not been heard yet: the player keeps about a
 * quarter of a second of sound queued ahead of the speaker. So each measurement is filed
 * under the moment it will actually come out, worked out by keeping count of how much
 * sound is queued, on a clock that stops while playback is paused. [read] then hands back
 * the measurement that belongs to now. Without this the display would run ahead of the
 * music by the length of the queue.
 *
 * It costs nothing while nobody is looking: the sums are skipped unless [read] has been
 * called in the last second.
 */
internal object AudioSpectrum {

    /** How many bands the sound is divided into. */
    const val Bands = SpectrumBands

    private const val Window = 2048
    private const val Hop = Window / 2
    private const val Kept = 128

    // ── Measuring. Touched only by the player's thread. ─────────────────────

    private var sampleRate = 44_100
    private var channels = 2

    /** The samples waiting to be looked at, as one channel, -1..1. */
    private val pending = FloatArray(Window)
    private var filled = 0

    private val real = FloatArray(Window)
    private val imaginary = FloatArray(Window)
    private val decibels = FloatArray(Bands)

    /** A bell-shaped weighting, so that the cut ends of the window do not read as pitches of their own. */
    private val taper = FloatArray(Window) { (0.5 - 0.5 * cos(2.0 * PI * it / (Window - 1))).toFloat() }
    private val cosines = FloatArray(Window / 2) { cos(2.0 * PI * it / Window).toFloat() }
    private val sines = FloatArray(Window / 2) { sin(2.0 * PI * it / Window).toFloat() }
    private val reversed = IntArray(Window) { Integer.reverse(it) ushr (32 - Integer.numberOfTrailingZeros(Window)) }

    /** Band b is made of the transform's outputs from `edges[b]` up to, not including, `edges[b + 1]`. */
    private val edges = IntArray(Bands + 1)

    /** Recorded music is quieter the higher it goes. This much is added to each band so the top of the display is not asleep. */
    private val lift = FloatArray(Bands)

    /** The level of the loudest band lately: the display is scaled to it, so a quiet record fills it too. */
    private var loudest = QuietestTop

    /** The same for each band by itself. See [measure] for how the two are weighed. */
    private val peaks = FloatArray(Bands) { Silence }

    /** When, on [clock], the sound queued so far will have finished coming out. */
    private var queuedUntil = 0L

    // ── Kept for the display. Written by the player's thread, read by the main one. ──

    private val times = LongArray(Kept)
    private val levels = FloatArray(Kept * Bands)

    /** How many measurements have been written, ever. The latest is at `(written - 1) % Kept`. */
    @Volatile private var written = 0

    @Volatile private var watchedAt = 0L

    // ── The clock: microseconds of playing time. It stands still while paused. ──

    @Volatile private var pausedAt = 0L
    @Volatile private var pausedFor = 0L

    init {
        layOutBands()
    }

    private fun wallClock(): Long = SystemClock.elapsedRealtimeNanos() / 1000

    private fun clock(): Long {
        val stopped = pausedAt
        return (if (stopped >= 0) stopped else wallClock()) - pausedFor
    }

    /**
     * Tells the clock whether sound is coming out. The queue ahead of the speaker is held,
     * not dropped, while playback is paused, so time has to stop with it. Main thread.
     */
    fun setPlaying(playing: Boolean) {
        val now = wallClock()
        val stopped = pausedAt
        if (playing && stopped >= 0) {
            pausedFor += now - stopped
            pausedAt = -1
        } else if (!playing && stopped < 0) {
            pausedAt = now
        }
    }

    /** A new stream, or a seek: whatever was queued is gone, and so is what was measured from it. */
    fun reset(sampleRateHz: Int, channelCount: Int) {
        if (sampleRateHz > 0 && sampleRateHz != sampleRate) {
            sampleRate = sampleRateHz
            layOutBands()
        }
        channels = max(1, channelCount)
        filled = 0
        queuedUntil = 0
        loudest = QuietestTop
        peaks.fill(Silence)
        // Nothing kept is due any more.
        for (index in times.indices) times[index] = Long.MAX_VALUE
    }

    /** One buffer of 16-bit PCM on its way to the speaker. */
    fun listen(samples: ShortBuffer) {
        val frames = samples.remaining() / channels
        if (frames == 0) return

        // Where this buffer falls in time: straight after what is already queued, or,
        // if the queue has run dry, as soon as sound can get out of the phone.
        val start = max(clock() + OutputDelay, queuedUntil)
        queuedUntil = start + frames * 1_000_000L / sampleRate

        if (SystemClock.elapsedRealtime() - watchedAt > WatchedFor) {
            filled = 0
            return
        }

        val base = samples.position()
        for (frame in 0 until frames) {
            val at = base + frame * channels
            val sample = if (channels == 1) samples.get(at).toFloat() else (samples.get(at) + samples.get(at + 1)) * 0.5f
            pending[filled++] = sample / 32768f
            if (filled == Window) {
                // Filed under the moment the middle of the window is heard.
                measure(heardAt = start + (frame + 1 - Window / 2) * 1_000_000L / sampleRate)
                System.arraycopy(pending, Hop, pending, 0, Window - Hop)
                filled = Window - Hop
            }
        }
    }

    /**
     * The measurement that belongs to this moment: fills the first [Bands] of [into] with
     * levels from 0 to 1, lowest notes first. Returns false, leaving [into] alone, when
     * there is none: nothing has played lately. Main thread; allocates nothing.
     */
    fun read(into: FloatArray): Boolean {
        watchedAt = SystemClock.elapsedRealtime()
        val now = clock()
        val newest = written - 1
        val oldest = max(0, newest - Kept + 1)
        // They are written in the order they will be heard, so the first one found that
        // is due, going back from the newest, is the one that is sounding.
        for (count in newest downTo oldest) {
            val slot = count % Kept
            val heardAt = times[slot]
            if (heardAt <= now) {
                if (now - heardAt > Stale) return false
                System.arraycopy(levels, slot * Bands, into, 0, Bands)
                return true
            }
        }
        return false
    }

    private fun measure(heardAt: Long) {
        for (index in 0 until Window) {
            real[index] = pending[index] * taper[index]
            imaginary[index] = 0f
        }
        transform()

        var top = Silence
        for (band in 0 until Bands) {
            var power = 0f
            for (bin in edges[band] until edges[band + 1]) {
                power += real[bin] * real[bin] + imaginary[bin] * imaginary[bin]
            }
            // Measured against a pure tone at full volume, which comes out as Window / 4.
            val level = 10f * log10(power / FullScale + 1e-12f) + lift[band]
            decibels[band] = level
            if (level > top) top = level
        }

        // The scale follows the loudest band: up at once, back down slowly. Below a
        // certain quiet it stops following, so that silence stays silent.
        loudest = max(top, loudest - FallPerMeasure)
        val ceiling = max(loudest, QuietestTop)

        val slot = written % Kept
        // Not due until it is whole: a reader that finds this slot half written sees a
        // time in the future and passes over it.
        times[slot] = Long.MAX_VALUE
        for (band in 0 until Bands) {
            // Each band is also measured against its own recent best, up to a point.
            // Against the loudest band alone, a record with a heavy bass would show a
            // bar in the middle and little else; a cymbal is far quieter than a kick
            // drum and no less a part of the music. So a band's own peak counts as the
            // top of its bar, unless that peak is further than [Catch] below the loudest
            // band's, which keeps what is nearly silent from being blown up into noise.
            peaks[band] = max(decibels[band], peaks[band] - FallPerMeasure)
            val best = max(peaks[band], ceiling - Catch)
            levels[slot * Bands + band] = ((decibels[band] - (best - Range)) / Range).coerceIn(0f, 1f)
        }
        times[slot] = heardAt
        written++
    }

    /** The fast Fourier transform, in place, on [real] and [imaginary]. */
    private fun transform() {
        for (index in 0 until Window) {
            val other = reversed[index]
            if (other > index) {
                val held = real[index]
                real[index] = real[other]
                real[other] = held
            }
        }
        var size = 2
        while (size <= Window) {
            val half = size / 2
            val step = Window / size
            var start = 0
            while (start < Window) {
                var turn = 0
                for (low in start until start + half) {
                    val high = low + half
                    val c = cosines[turn]
                    val s = sines[turn]
                    val r = real[high] * c + imaginary[high] * s
                    val i = imaginary[high] * c - real[high] * s
                    real[high] = real[low] - r
                    imaginary[high] = imaginary[low] - i
                    real[low] += r
                    imaginary[low] += i
                    turn += step
                }
                start += size
            }
            size *= 2
        }
    }

    /**
     * Spaces the bands evenly by pitch between [LowestHz] and [HighestHz], which is to say
     * each one a fixed ratio above the last. At the bottom the transform cannot tell
     * pitches that close apart, so every band is given at least one output of its own.
     */
    private fun layOutBands() {
        val binHz = sampleRate.toFloat() / Window
        val lastBin = Window / 2 - 1
        var previous = 0
        for (edge in 0..Bands) {
            val hz = LowestHz * (HighestHz / LowestHz).pow(edge.toFloat() / Bands)
            val bin = max((hz / binHz).roundToInt(), previous + 1).coerceAtMost(lastBin - (Bands - edge))
            edges[edge] = bin
            previous = bin
        }
        for (band in 0 until Bands) {
            val middleHz = (edges[band] + edges[band + 1]) * 0.5f * binHz
            lift[band] = (LiftPerOctave * log2(middleHz / LiftFromHz)).coerceIn(0f, MostLift)
        }
    }

    private const val LowestHz = 45f
    private const val HighestHz = 15_000f

    private const val FullScale = (Window / 4f) * (Window / 4f)
    private const val Silence = -120f

    /** From the bottom of a bar to its top, in decibels. */
    private const val Range = 30f

    /** How far below the loudest band another band's peak may be and still fill its bar. */
    private const val Catch = 16f

    /** The display is never scaled up to anything quieter than this. */
    private const val QuietestTop = -46f

    /** How fast the scale lets go of a loud passage: about 8 dB a second. */
    private const val FallPerMeasure = 8f * Hop / 44_100f

    private const val LiftFromHz = 1_000f
    private const val LiftPerOctave = 2.5f
    private const val MostLift = 9f

    /** From the end of the player's queue to the air, roughly, in microseconds. */
    private const val OutputDelay = 80_000L

    /** A measurement this long past is not what is sounding; playback has stopped. Microseconds. */
    private const val Stale = 250_000L

    /** Milliseconds since anyone last asked, after which measuring stops. */
    private const val WatchedFor = 1_000L
}
