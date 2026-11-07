package app.djungle.backbeat.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp

/** A hit the microphone heard: the frame of the input it began on, and how loud it got. */
class Onset(val frame: Long, val peak: Float)

/**
 * Finds the moments something is struck in a stream of microphone audio: a clap, a stick on a
 * pad, a drum, a plucked string.
 *
 * It follows the level of the sound with a quick peak follower and listens for a sudden rise:
 * a hit is a level at least [rise] times the loudest it had been a few milliseconds before. So
 * a quiet ghost note just after a loud hit is still heard, as long as the loud one has died
 * down a little, but the ring of a cymbal or the hum of a room is not.
 *
 * Having heard one, it looks at the next few milliseconds to see how loud the hit got, then
 * goes back to the first frame that reached a share of that peak. That is where the hit
 * started, to within a fraction of a millisecond for anything struck.
 *
 * A *shadow* (see [shadow]) raises the bar for a stretch of frames. The metronome's own click,
 * heard through the phone's microphone, falls under one: it is only taken as a hit if it is
 * well louder than the click could be on its own, and then the hit is placed where the sound
 * rose past the click's level, which is where the player's hit began, not the click's.
 *
 * It is plain arithmetic per frame, cheap enough for the audio thread.
 */
class OnsetDetector(
    private val sampleRate: Int,
    /** The quietest hit it will hear, as a peak level after the filter. */
    private val floor: Float = 0.004f,
    /** How far a hit must rise over the level just before it. */
    private val rise: Float = 3f,
) {
    /** Two one-pole high-passes at 150 Hz, to ignore rumble, footsteps, breath and handling. */
    private val hpCoefficient: Float = run {
        val rc = 1.0 / (2 * PI * 150.0)
        (rc / (rc + 1.0 / sampleRate)).toFloat()
    }
    private var hpIn = 0f
    private var hpMid = 0f
    private var hpOut = 0f

    /** The peak follower: it jumps up at once and lets go over a few milliseconds. */
    private val release = exp(-1.0 / (0.003 * sampleRate)).toFloat()
    private var follower = 0f

    /** The follower's highest value in each of the last few milliseconds, for "just before". */
    private val perMillisecond = (sampleRate / 1000).coerceAtLeast(1)
    private val history = FloatArray(HISTORY_MS)
    private var millis = 0L
    private var millisFill = 0
    private var millisPeak = 0f

    /** The loudest it was between [LOOK_BACK_FAR] and [LOOK_BACK_NEAR] milliseconds ago. */
    private var before = 0f

    /** The filtered signal's recent frames, to find where a hit began once its peak is known. */
    private val recent = FloatArray(Integer.highestOneBit((sampleRate * 0.032).toInt()) * 2)
    private val recentMask = recent.size - 1

    /** Frames handed in so far. */
    var frame = 0L
        private set

    // A hit being measured: where it was noticed, how loud it is so far, and what it had to beat.
    private var measuring = false
    private var noticedAt = 0L
    private var measureUntil = 0L
    private var peak = 0f
    private var bar = 0f
    private var quietBefore = 0f

    /** No hit before this frame: the last one is too recent, or it has not yet heard the room. */
    private var quietUntil = WARM_UP_MS.toLong() * perMillisecond

    private class Shadow(val from: Long, val to: Long, val level: Float)

    private val shadows = ArrayList<Shadow>()

    /**
     * Between frames [from] and [to] (inclusive) of the input, a hit must reach [level], and it
     * is placed where it rose past that level. For the metronome's own click: see the class notes.
     */
    fun shadow(from: Long, to: Long, level: Float) {
        if (to < frame || level <= 0f) return
        shadows += Shadow(from, to, level)
    }

    /** The bar a hit must clear at [at], from the shadows over it. */
    private fun shadowAt(at: Long): Float {
        var level = 0f
        var i = 0
        while (i < shadows.size) {
            val s = shadows[i]
            if (s.to < at - recent.size) {
                shadows.removeAt(i)
                continue
            }
            if (at in s.from..s.to && s.level > level) level = s.level
            i++
        }
        return level
    }

    /** Listens to the first [count] frames of [samples], calling [onOnset] for each hit heard. */
    fun process(samples: FloatArray, count: Int, onOnset: (Onset) -> Unit) {
        for (i in 0 until count) {
            val x = samples[i]
            val mid = hpCoefficient * (hpMid + x - hpIn)
            hpOut = hpCoefficient * (hpOut + mid - hpMid)
            hpIn = x
            hpMid = mid
            val level = abs(hpOut)
            recent[(frame and recentMask.toLong()).toInt()] = level
            follower = if (level > follower) level else follower * release

            if (measuring) {
                if (level > peak) peak = level
                if (frame >= measureUntil) {
                    measuring = false
                    finish(onOnset)
                }
            } else if (frame >= quietUntil && follower > rise * before && follower > floor) {
                val shade = shadowAt(frame)
                if (follower > shade) {
                    measuring = true
                    noticedAt = frame
                    measureUntil = frame + MEASURE_MS * perMillisecond
                    peak = follower
                    bar = shade
                    quietBefore = before
                }
            }

            // Keep the per-millisecond history, and with it the level "just before".
            if (follower > millisPeak) millisPeak = follower
            if (++millisFill >= perMillisecond) {
                history[(millis % HISTORY_MS).toInt()] = millisPeak
                millis++
                millisFill = 0
                millisPeak = 0f
                var loudest = 0f
                for (back in LOOK_BACK_NEAR..LOOK_BACK_FAR) {
                    val m = millis - back
                    if (m >= 0) loudest = maxOf(loudest, history[(m % HISTORY_MS).toInt()])
                }
                before = loudest
            }
            frame++
        }
    }

    /** The hit's peak is known: find where it began, and hand it on. */
    private fun finish(onOnset: (Onset) -> Unit) {
        // A level the hit reached but nothing before it did: the tail of what came before, the
        // click in a shadow, or simply a share of the hit's own peak.
        val mark = maxOf(peak * START_SHARE, quietBefore * 1.5f, bar, floor * 0.5f)
        val from = maxOf(noticedAt - LOOK_BACK_NEAR * perMillisecond, frame - recent.size + 1, 0L)
        var start = noticedAt
        var f = from
        while (f <= frame) {
            if (recent[(f and recentMask.toLong()).toInt()] >= mark) {
                start = f
                break
            }
            f++
        }
        quietUntil = noticedAt + MIN_GAP_MS * perMillisecond
        onOnset(Onset(start, peak))
    }

    private companion object {
        /** How long it listens to the room before it will call anything a hit. */
        const val WARM_UP_MS = 50

        /** Milliseconds of follower history kept. */
        const val HISTORY_MS = 32

        /** "Just before" a frame means this many milliseconds back, from near to far. */
        const val LOOK_BACK_NEAR = 3
        const val LOOK_BACK_FAR = 20

        /** How long a hit is listened to before its peak is taken. */
        const val MEASURE_MS = 6

        /** The share of its peak at which a hit is said to have started. */
        const val START_SHARE = 0.3f

        /** No two hits closer than this: a flam is one hit. Quick enough for 16ths at 300 BPM. */
        const val MIN_GAP_MS = 35
    }
}
