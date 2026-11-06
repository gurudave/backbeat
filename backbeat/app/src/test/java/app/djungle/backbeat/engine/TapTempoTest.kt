package app.djungle.backbeat.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TapTempoTest {

    private fun TapTempo.tapEvery(millis: Long, times: Int, from: Long = 0): Int? {
        var last: Int? = null
        repeat(times) { last = tap(from + it * millis) }
        return last
    }

    @Test
    fun steadyTapsGiveTheirTempo() {
        assertEquals(120, TapTempo().tapEvery(500, 4))
        assertEquals(60, TapTempo().tapEvery(1000, 3))
    }

    @Test
    fun oneTapIsNotATempo() {
        assertNull(TapTempo().tap(1000))
    }

    @Test
    fun aWobblyTapIsAveragedOut() {
        val tapper = TapTempo()
        listOf(0L, 500L, 1000L, 1540L, 2000L).forEach { tapper.tap(it) }
        assertEquals(120, tapper.tap(2500))
    }

    @Test
    fun aPauseStartsAfresh() {
        val tapper = TapTempo()
        tapper.tapEvery(500, 4)
        assertNull(tapper.tap(10_000))
        assertEquals(60, tapper.tap(11_000))
    }

    @Test
    fun aNewTempoIsPickedUpAtOnce() {
        val tapper = TapTempo()
        tapper.tapEvery(500, 5)
        // Suddenly twice as slow.
        assertEquals(60, tapper.tap(2000 + 1000))
    }

    @Test
    fun theSlowestTempoCanBeTapped() {
        assertEquals(MIN_BPM, TapTempo().tapEvery(3000, 3))
    }
}
