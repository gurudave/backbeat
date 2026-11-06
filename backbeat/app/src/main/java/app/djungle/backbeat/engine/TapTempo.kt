package app.djungle.backbeat.engine

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns taps on the TAP key into a tempo. It averages the last few taps, so one wobbly tap
 * barely moves it, but starts afresh after a pause, or when the taps suddenly speed up or
 * slow down, so a new tempo is picked up within two taps.
 */
class TapTempo(private val keep: Int = 6) {
    private val taps = ArrayDeque<Long>()

    /** Records a tap at [atMillis] and returns the tempo so far, or null if it is the first. */
    fun tap(atMillis: Long): Int? {
        val last = taps.lastOrNull()
        if (last != null) {
            val interval = atMillis - last
            if (interval <= 0 || interval > RESET_AFTER_MILLIS) {
                taps.clear()
            } else if (taps.size >= 2) {
                val average = (last - taps.first()).toDouble() / (taps.size - 1)
                if (abs(interval - average) > average * 0.4) {
                    // A clear change of tempo: keep only the last tap and this one.
                    taps.clear()
                    taps.addLast(last)
                }
            }
        }
        taps.addLast(atMillis)
        while (taps.size > keep) taps.removeFirst()
        if (taps.size < 2) return null
        val span = taps.last() - taps.first()
        return (60_000.0 * (taps.size - 1) / span).roundToInt().coerceIn(MIN_BPM, MAX_BPM)
    }

    fun clear() = taps.clear()

    private companion object {
        /** A little longer than one beat at the slowest tempo. */
        const val RESET_AFTER_MILLIS = 60_000L / MIN_BPM + 500L
    }
}
