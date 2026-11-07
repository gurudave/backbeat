package app.djungle.backbeat.engine

import kotlin.math.abs

/**
 * Turns the speaker's clock into a steady one for the display.
 *
 * The sound card reports which frame is playing in small, uneven steps, and its first reports
 * after START are often revised. Drawn directly, that makes the ball judder and, worse, jump
 * back. This clock runs on by itself at the sample rate, and each reading only nudges it a
 * little towards the speaker, so small errors are eased out over a few display frames. It
 * never runs backwards: if the speaker turns out to be behind, the clock slows until the
 * speaker catches up. Only a large disagreement (after a pause, say) makes it jump.
 */
class DisplayClock(private val sampleRate: Int) {
    private var shown = Double.NaN
    private var lastNanos = 0L

    /** Forgets everything, ready for a new START. */
    fun reset() {
        shown = Double.NaN
    }

    /**
     * Given that the speaker reads [raw] frames at [nanos] (from [System.nanoTime]), returns the
     * frame the display should show then.
     */
    fun show(raw: Double, nanos: Long): Double {
        if (shown.isNaN()) {
            shown = raw
            lastNanos = nanos
            return shown
        }
        val seconds = (nanos - lastNanos).coerceAtLeast(0L) / 1e9
        lastNanos = nanos
        val predicted = shown + seconds * sampleRate
        val error = raw - predicted
        val next = if (abs(error) > sampleRate * SNAP_SECONDS) raw else predicted + error * PULL
        shown = maxOf(shown, next)
        return shown
    }

    private companion object {
        /** Share of the error taken back each reading: about a sixth of a second to settle at 60 Hz. */
        const val PULL = 0.1

        /** Further off than this, and the clock jumps rather than easing. */
        const val SNAP_SECONDS = 0.3
    }
}
