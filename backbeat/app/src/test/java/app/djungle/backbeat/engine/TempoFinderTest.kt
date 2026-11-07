package app.djungle.backbeat.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import kotlin.random.Random

class TempoFinderTest {
    private val rate = 48_000

    /** A drum loop at [bpm]: kick on one and three, snare on two and four, hi-hats in quavers. */
    private fun loop(bpm: Double, seconds: Int, swing: Boolean = false, seed: Int = 3): FloatArray {
        val drums = Synth.render(Kit.DRUMS, rate)
        val random = Random(seed)
        val out = FloatArray(rate * seconds) { ((random.nextDouble() * 2 - 1) * 0.01).toFloat() }
        val beat = rate * 60.0 / bpm
        var n = 0
        while (n * beat < out.size) {
            val at = (n * beat).toInt()
            out.mix(if (n % 2 == 0) drums.strong else drums.normal, at, 0.7f)
            out.mix(drums.sub, at, 0.5f)
            out.mix(drums.sub, (at + beat * (if (swing) 2.0 / 3 else 0.5)).toInt(), 0.35f)
            n++
        }
        return out
    }

    private fun FloatArray.mix(sound: FloatArray, at: Int, gain: Float) {
        for (i in sound.indices) if (at + i < size) this[at + i] += sound[i] * gain
    }

    private fun listen(signal: FloatArray): TempoGuess? {
        val finder = TempoFinder(rate)
        val block = FloatArray(240)
        var at = 0
        while (at + block.size <= signal.size) {
            System.arraycopy(signal, at, block, 0, block.size)
            finder.feed(block, block.size)
            at += block.size
        }
        return finder.estimate()
    }

    @Test
    fun drumLoopsAreTimedToAFractionOfABpm() {
        for (bpm in listOf(84.0, 97.0, 123.0, 140.0, 172.0)) {
            val guess = listen(loop(bpm, 12))!!
            assertEquals("at $bpm", bpm, guess.bpm, 0.4)
            assertTrue("at $bpm, sure only ${guess.confidence}", guess.confidence > 0.5f)
        }
    }

    @Test
    fun aSwungGrooveIsTimedByItsBeat() {
        assertEquals(110.0, listen(loop(110.0, 12, swing = true))!!.bpm, 0.4)
    }

    @Test
    fun anOddTempoIsNotRoundedAway() {
        assertEquals(128.5, listen(loop(128.5, 12))!!.bpm, 0.25)
    }

    @Test
    fun aFewSecondsAreNotEnough() {
        assertNull(listen(loop(120.0, 3)))
    }

    @Test
    fun noiseIsNotMusic() {
        val random = Random(5)
        val hiss = FloatArray(rate * 10) { ((random.nextDouble() * 2 - 1) * 0.05).toFloat() }
        val guess = listen(hiss)
        assertTrue("noise heard as ${guess?.bpm} with ${guess?.confidence}", guess == null || guess.confidence < 0.3f)
    }

    @Test
    fun halfAndDoubleAreOffered() {
        assertEquals(listOf(60, 240), TempoGuess(120.0, 1f).alternatives)
        assertEquals(listOf(140), TempoGuess(280.0, 1f).alternatives)
    }

    @Test
    fun theTransformIsRight() {
        val n = 64
        val fft = Fft(n)
        val re = DoubleArray(n) { cos(2 * PI * 5 * it / n) + 0.5 * sin(2 * PI * 12 * it / n) }
        val im = DoubleArray(n)
        fft.transform(re, im)
        assertEquals(n / 2.0, re[5], 1e-9)
        assertEquals(-n / 4.0, im[12], 1e-9)
        assertTrue((0 until n).filter { it !in listOf(5, 12, n - 5, n - 12) }.all { abs(re[it]) < 1e-9 && abs(im[it]) < 1e-9 })
    }
}
