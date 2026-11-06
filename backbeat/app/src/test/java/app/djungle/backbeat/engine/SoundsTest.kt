package app.djungle.backbeat.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SoundsTest {
    private val rate = 48_000

    private fun peak(x: FloatArray, from: Int = 0, to: Int = x.size) = (from until to).maxOfOrNull { abs(x[it]) } ?: 0f

    @Test
    fun everySoundIsCleanAndShort() {
        for (kit in Kit.entries) {
            val sounds = Synth.render(kit, rate)
            listOf("strong" to sounds.strong, "normal" to sounds.normal, "sub" to sounds.sub).forEach { (name, x) ->
                val label = "${kit.label} $name"
                assertTrue("$label is empty", x.isNotEmpty())
                assertTrue("$label lasts ${x.size} frames", x.size < rate / 2)
                assertTrue("$label clips", peak(x) <= 1f)
                assertTrue("$label is too quiet", peak(x) > 0.2f)
                // No sound starts or stops with a click of its own.
                assertTrue("$label starts at ${x.first()}", abs(x.first()) < 0.01f)
                assertTrue("$label ends at ${x.last()}", abs(x.last()) < 0.01f)
                assertTrue("$label is not a number", x.none { it.isNaN() })
            }
            assertTrue("${kit.label}: the in-between click should be quieter", peak(sounds.sub) < peak(sounds.normal))
            assertTrue("${kit.label}: the accent should not be quieter", peak(sounds.strong) >= peak(sounds.normal))
        }
    }

    @Test
    fun theSameKitSoundsTheSameEveryTime() {
        for (kit in Kit.entries) {
            assertTrue(kit.label, Synth.render(kit, rate).normal.contentEquals(Synth.render(kit, rate).normal))
        }
    }

    @Test
    fun theMixerAddsVoicesAndCarriesThemAcrossBlocks() {
        val mixer = Mixer()
        val one = FloatArray(6) { 0.1f }
        mixer.trigger(one, 1f, 2)
        mixer.trigger(one, 2f, 4)
        val out = FloatArray(4)
        mixer.render(out, 4, 1f)
        assertEquals(listOf(0f, 0f, 0.1f, 0.1f), out.toList())
        mixer.render(out, 4, 1f)
        assertEquals(0.1f + 0.2f, out[0], 1e-6f)
        assertEquals(0.1f + 0.2f, out[3], 1e-6f)
        mixer.render(out, 4, 1f)
        assertEquals(listOf(0.2f, 0.2f, 0f, 0f), out.toList().map { (it * 1e6f).toInt() / 1e6f })
    }

    @Test
    fun theMixerNeverClips() {
        val mixer = Mixer()
        repeat(5) { mixer.trigger(FloatArray(10) { 0.9f }, 1f, 0) }
        val out = FloatArray(10)
        mixer.render(out, 10, 1f)
        assertTrue(out.all { it <= 1f })
    }

    @Test
    fun eachClickIsHeardOnItsFrame() {
        val take = Bounce.render(Groove(bpm = 120, kit = Kit.BEEP), rate, rate * 4)
        assertEquals(8, take.ticks.size)
        for ((frame, _) in take.ticks) {
            if (frame > 0) assertEquals("before $frame", 0f, take.audio[frame - 1])
            assertTrue("at $frame", peak(take.audio, frame, frame + 200) > 0.1f)
        }
    }

    @Test
    fun ghostsAreQuietAndRestsAreSilent() {
        val accents = listOf(Accent.NORMAL, Accent.SOFT, Accent.MUTE, Accent.NORMAL)
        val take = Bounce.render(Groove(bpm = 120, kit = Kit.WOOD, accents = accents, volume = 1f), rate, rate * 2)
        val beat = 24_000
        val levels = (0 until 4).map { peak(take.audio, it * beat, (it + 1) * beat) }
        assertTrue(levels[1] < levels[0] * 0.5f)
        assertEquals(0f, levels[2])
        assertEquals(levels[0], levels[3], 1e-6f)
    }

    @Test
    fun silentBarsAreSilent() {
        val practice = Practice(gap = true, gapPlay = 1, gapRest = 1)
        val take = Bounce.render(Groove(bpm = 240), rate, rate * 3, practice)
        val bar = rate
        assertTrue(peak(take.audio, 0, bar) > 0.1f)
        assertEquals(0f, peak(take.audio, bar + 4_000, 2 * bar))
        assertTrue(peak(take.audio, 2 * bar, 3 * bar) > 0.1f)
    }

    @Test
    fun volumeIsSquared() {
        assertEquals(1f, loudness(1f), 0f)
        assertEquals(0.25f, loudness(0.5f), 1e-6f)
    }
}
