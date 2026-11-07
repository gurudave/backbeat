package app.djungle.backbeat.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * The onset detector, played synthetic performances with known timings: claps, plucked
 * strings, drums, a noisy room, and the metronome's own click leaking into the microphone.
 */
class OnsetDetectorTest {
    private val rate = 48_000
    private val ms = rate / 1000

    /** A hand clap: a burst of bright noise that rises in a third of a millisecond and dies away. */
    private fun clap(peak: Float, seed: Int): FloatArray {
        val random = Random(seed)
        val attack = (rate * 0.0003).toInt()
        val out = FloatArray(rate * 60 / 1000) { i ->
            val t = i.toDouble() / rate
            val env = if (i < attack) i.toDouble() / attack else exp(-(t - attack.toDouble() / rate) / 0.012)
            ((random.nextDouble() * 2 - 1) * env).toFloat()
        }
        return normalised(out, peak)
    }

    /** A plucked string (Karplus-Strong): a burst of noise fed round a delay the length of one cycle. */
    private fun pluck(freq: Double, peak: Float, seed: Int): FloatArray {
        val random = Random(seed)
        val period = (rate / freq).toInt()
        val line = FloatArray(period) { (random.nextDouble() * 2 - 1).toFloat() }
        val out = FloatArray(rate * 2 / 5)
        var at = 0
        for (i in out.indices) {
            val next = (at + 1) % period
            val v = line[at]
            line[at] = 0.996f * 0.5f * (line[at] + line[next])
            out[i] = v
            at = next
        }
        return normalised(out, peak)
    }

    private fun normalised(x: FloatArray, peak: Float): FloatArray {
        val loudest = x.maxOf { abs(it) }
        return FloatArray(x.size) { x[it] / loudest * peak }
    }

    /** A quiet room: a little hiss, and a mains hum. */
    private fun room(frames: Int, hiss: Float = 0.002f, hum: Float = 0f, seed: Int = 99): FloatArray {
        val random = Random(seed)
        return FloatArray(frames) { i ->
            var noise = 0.0
            repeat(4) { noise += random.nextDouble() * 2 - 1 }
            (noise / 2 * hiss + hum * sin(2 * PI * 50.0 * i / rate)).toFloat()
        }
    }

    private fun FloatArray.add(sound: FloatArray, at: Int, gain: Float = 1f) {
        for (i in sound.indices) if (at + i in indices) this[at + i] += sound[i] * gain
    }

    private fun detect(signal: FloatArray, block: Int = 240, setUp: (OnsetDetector) -> Unit = {}): List<Onset> {
        val detector = OnsetDetector(rate)
        setUp(detector)
        val found = ArrayList<Onset>()
        val buffer = FloatArray(block)
        var at = 0
        while (at < signal.size) {
            val n = minOf(block, signal.size - at)
            System.arraycopy(signal, at, buffer, 0, n)
            detector.process(buffer, n) { found += it }
            at += n
        }
        return found
    }

    /** Checks that [found] are exactly [expected], each within [tolerance] frames. */
    private fun assertHits(expected: List<Int>, found: List<Onset>, tolerance: Int) {
        assertEquals("found ${found.map { it.frame }}, expected $expected", expected.size, found.size)
        expected.zip(found).forEach { (want, got) ->
            assertTrue("hit at $want found at ${got.frame}", abs(got.frame - want) <= tolerance)
        }
    }

    @Test
    fun clapsOfEveryLoudnessArePlacedWithinAMillisecond() {
        val random = Random(1)
        val signal = room(rate * 6)
        val times = ArrayList<Int>()
        var at = rate / 4
        var n = 0
        while (at < signal.size - rate / 10) {
            times += at
            // From a soft clap across the room to one right by the phone.
            signal.add(clap(0.03f + 0.8f * random.nextFloat(), seed = n++), at)
            at += (rate * (0.09 + 0.3 * random.nextDouble())).toInt()
        }
        val found = detect(signal)
        assertHits(times, found, tolerance = ms)
        val meanError = times.zip(found).map { (want, got) -> (got.frame - want).toDouble() }.average()
        assertTrue("on average ${meanError / ms} ms out", abs(meanError) < 0.4 * ms)
    }

    @Test
    fun pluckedStringsAreHeard() {
        val signal = room(rate * 3)
        val times = listOf(rate / 4, rate, rate * 7 / 4, rate * 5 / 2)
        times.forEachIndexed { n, at -> signal.add(pluck(110.0 * (n + 1), 0.4f, seed = n), at) }
        assertHits(times, detect(signal), tolerance = 2 * ms)
    }

    @Test
    fun drumsAreHeard() {
        val drums = Synth.render(Kit.DRUMS, rate)
        val signal = room(rate * 2)
        val times = listOf(4_800, 16_800, 28_800, 40_800, 52_800, 64_800)
        times.forEachIndexed { n, at -> signal.add(listOf(drums.strong, drums.sub, drums.normal)[n % 3], at, 0.6f) }
        assertHits(times, detect(signal), tolerance = ms)
    }

    @Test
    fun aQuietRoomWithAHumIsNotAHit() {
        assertEquals(emptyList<Long>(), detect(room(rate * 10, hiss = 0.004f, hum = 0.08f)).map { it.frame })
    }

    @Test
    fun aGhostNoteJustAfterALoudHitIsHeard() {
        val signal = room(rate)
        signal.add(clap(0.8f, seed = 1), 4_800)
        signal.add(clap(0.08f, seed = 2), 4_800 + 120 * ms)
        assertHits(listOf(4_800, 4_800 + 120 * ms), detect(signal), tolerance = ms)
    }

    @Test
    fun aFlamIsOneHit() {
        val signal = room(rate)
        signal.add(clap(0.5f, seed = 1), 4_800)
        signal.add(clap(0.5f, seed = 2), 4_800 + 15 * ms)
        assertHits(listOf(4_800), detect(signal), tolerance = ms)
    }

    /**
     * The metronome's click leaks into the microphone, with a reflection off the table. Under a
     * shadow it is ignored, while a clap played with it, a little early or late, is heard and
     * placed where the clap began.
     */
    @Test
    fun theClickInItsShadowIsIgnoredButAClapWithItIsNot() {
        val blip = Synth.render(Kit.BLIP, rate).normal
        val echo = 0.12f
        val beat = rate / 2
        val signal = room(rate * 8)
        val clicks = (1 until 15).map { it * beat }
        val claps = ArrayList<Int>()
        clicks.forEachIndexed { n, at ->
            signal.add(blip, at, echo)
            signal.add(blip, at + 5 * ms, echo * 0.3f)
            // Every other click, a clap: early, late, on it, and very nearly on it.
            if (n % 2 == 0) {
                val offset = listOf(-12, 15, 0, 3, -4, 22, 9)[n / 2 % 7] * ms
                signal.add(clap(0.6f, seed = n), at + offset)
                claps += at + offset
            }
        }
        val found = detect(signal) { detector ->
            clicks.forEach { detector.shadow(it - 6L * ms, it + 30L * ms, echo * 2.5f) }
        }
        assertHits(claps, found, tolerance = 3 * ms / 2)
    }

    @Test
    fun withoutAShadowTheClickIsHeardLikeAnythingElse() {
        val blip = Synth.render(Kit.BLIP, rate).normal
        val signal = room(rate * 2)
        val clicks = listOf(rate / 4, rate * 3 / 4, rate * 5 / 4)
        clicks.forEach { signal.add(blip, it, 0.12f) }
        assertHits(clicks, detect(signal), tolerance = ms / 2)
    }

    @Test
    fun theBlockSizeMakesNoDifference() {
        val signal = room(rate * 2)
        listOf(3_000, 20_000, 41_000, 70_000).forEachIndexed { n, at -> signal.add(clap(0.3f, seed = n), at) }
        val reference = detect(signal, block = 240).map { it.frame }
        for (block in listOf(1, 64, 999, 4096)) assertEquals("block $block", reference, detect(signal, block).map { it.frame })
    }
}
