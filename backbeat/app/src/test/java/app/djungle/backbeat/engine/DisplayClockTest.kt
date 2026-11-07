package app.djungle.backbeat.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class DisplayClockTest {
    private val rate = 48_000
    private val frame = 16_666_667L

    @Test
    fun aSteadySpeakerIsFollowedExactly() {
        val clock = DisplayClock(rate)
        for (n in 0 until 120) {
            val nanos = n * frame
            assertEquals(nanos / 1e9 * rate, clock.show(nanos / 1e9 * rate, nanos), 1e-6)
        }
    }

    @Test
    fun itNeverRunsBackwards() {
        val clock = DisplayClock(rate)
        val random = Random(7)
        var last = Double.NEGATIVE_INFINITY
        for (n in 0 until 600) {
            val nanos = n * frame
            // Readings that arrive in lumps and wobble by up to 10 ms either way.
            val raw = nanos / 1e9 * rate + (random.nextDouble() * 2 - 1) * rate * 0.01
            val shown = clock.show(raw, nanos)
            assertTrue("frame $n went back from $last to $shown", shown >= last)
            last = shown
        }
    }

    @Test
    fun jitterIsEasedOut() {
        val clock = DisplayClock(rate)
        val random = Random(11)
        var worstStep = 0.0
        var previous = 0.0
        for (n in 0 until 600) {
            val nanos = n * frame
            val raw = nanos / 1e9 * rate + (random.nextDouble() * 2 - 1) * rate * 0.01
            val shown = clock.show(raw, nanos)
            if (n > 0) worstStep = maxOf(worstStep, abs((shown - previous) - rate * frame / 1e9))
            previous = shown
        }
        // Readings wobble by up to 480 frames; the steps shown wobble by far less.
        assertTrue("worst wobble $worstStep frames", worstStep < 120)
    }

    @Test
    fun aRevisedClockIsCaughtUpWithoutAJumpBack() {
        val clock = DisplayClock(rate)
        // The first readings run 100 ms ahead of the speaker, then the truth arrives.
        val ahead = rate * 0.1
        var last = 0.0
        for (n in 0 until 10) last = clock.show(n * frame / 1e9 * rate + ahead, n * frame)
        val shownAfter = (10 until 100).map { n -> clock.show(n * frame / 1e9 * rate, n * frame) }
        assertTrue(shownAfter.first() >= last)
        shownAfter.zipWithNext().forEach { (a, b) -> assertTrue(b >= a) }
        // Within a second and a half it agrees with the speaker to the millisecond.
        assertEquals(99 * frame / 1e9 * rate, shownAfter.last(), rate * 0.001)
    }

    @Test
    fun aBigJumpForwardIsTakenAtOnce() {
        val clock = DisplayClock(rate)
        clock.show(0.0, 0)
        assertEquals(rate * 2.0, clock.show(rate * 2.0, frame), 1e-6)
    }

    @Test
    fun resetStartsAfresh() {
        val clock = DisplayClock(rate)
        clock.show(rate * 5.0, 0)
        clock.reset()
        assertEquals(0.0, clock.show(0.0, frame), 1e-6)
    }
}
