package app.djungle.backbeat.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The Ear's whole chain, as on the phone: the metronome rendered through its own engine, heard
 * by the microphone at a quarter of its level, and a player clapping along a touch late and a
 * little loose, sometimes in silent bars. The detector, with a shadow under every click, and
 * the judge go over it, and what they make of each clap is set against the truth.
 */
class EarChainTest {
    private val rate = 48_000

    private fun clap(seed: Int, peak: Float): FloatArray {
        val random = Random(seed)
        val attack = (rate * 0.0003).toInt()
        val out = FloatArray(rate * 60 / 1000) { i ->
            val t = i.toDouble() / rate
            val env = if (i < attack) i.toDouble() / attack else exp(-(t - attack.toDouble() / rate) / 0.012)
            ((random.nextDouble() * 2 - 1) * env).toFloat()
        }
        val loudest = out.maxOf { abs(it) }
        return FloatArray(out.size) { out[it] / loudest * peak }
    }

    private fun session(groove: Groove, practice: Practice, seconds: Int, meanMillis: Double, spreadMillis: Double): Pair<Scorecard, List<Double>> {
        val frames = rate * seconds
        val take = Bounce.render(groove, rate, frames, practice)
        val sounds = Synth.render(groove.kit, rate)
        val gain = loudness(groove.volume)
        val echo = 0.25f
        val delay = 20
        val random = Random(3)
        val heard = FloatArray(frames) { (random.nextFloat() * 2 - 1) * 0.003f }
        for (i in 0 until frames - delay) heard[i + delay] += take.audio[i] * echo
        val truth = ArrayList<Pair<Int, Double>>()
        take.ticks.filter { it.second.isBeat && it.second.bar > 0 }.forEachIndexed { n, (frame, _) ->
            if (n % 7 == 3) return@forEachIndexed
            var g = 0.0
            repeat(6) { g += random.nextDouble() }
            val offset = meanMillis + (g - 3.0) / 0.7071 * spreadMillis
            val at = frame + (offset * rate / 1000).roundToInt()
            val sound = clap(n, 0.5f + 0.25f * random.nextFloat())
            for (i in sound.indices) if (at + i in heard.indices) heard[at + i] += sound[i]
            truth += at to offset
        }
        val detector = OnsetDetector(rate)
        take.ticks.forEach { (frame, tick) ->
            val level = sounds.peak(tick) * gain
            if (level > 0f) detector.shadow(frame + delay - rate * 6L / 1000, frame + delay + rate * 30L / 1000, echo * level * 2.5f)
        }
        val onsets = ArrayList<Onset>()
        val block = FloatArray(240)
        var at = 0
        while (at < frames) {
            val n = minOf(block.size, frames - at)
            System.arraycopy(heard, at, block, 0, n)
            detector.process(block, n) { onsets += it }
            at += n
        }
        assertEquals("claps heard", truth.size, onsets.size)
        val judge = Judge(groove.bpm)
        val clicks = take.ticks.map { (frame, tick) -> Click(frame * 1_000_000_000L / rate, tick) }
        val errors = onsets.mapIndexed { i, onset ->
            val nanos = onset.frame * 1_000_000_000L / rate
            val judgment = judge.judge(nanos, clicks.lastOrNull { it.nanos <= nanos }, clicks.firstOrNull { it.nanos > nanos })!!
            judgment.offsetMillis - truth[i].second
        }
        return judge.card to errors
    }

    @Test
    fun aLoosePlayerOverTheClicksEchoIsHeardAndJudgedTruly() {
        val groove = Groove(bpm = 120, kit = Kit.BLIP, subdivision = Subdivision.EIGHTHS)
        val (card, errors) = session(groove, Practice(), seconds = 12, meanMillis = 6.0, spreadMillis = 7.0)
        assertTrue("worst ${errors.maxOf { abs(it) }} ms off", errors.all { abs(it) <= 1.5 })
        assertEquals(6f, card.all.meanMillis, 2.5f)
        assertEquals("dragging", card.all.verdict)
    }

    @Test
    fun everyKitsEchoIsShadowed() {
        for (kit in Kit.entries) {
            val (_, errors) = session(Groove(bpm = 100, kit = kit), Practice(), seconds = 8, meanMillis = 0.0, spreadMillis = 10.0)
            assertTrue("${kit.label}: worst ${errors.maxOf { abs(it) }} ms off", errors.all { abs(it) <= 1.5 })
        }
    }

    @Test
    fun hitsInSilentBarsAreJudgedOnTheirOwn() {
        val practice = Practice(gap = true, gapPlay = 2, gapRest = 2)
        val (card, _) = session(Groove(bpm = 110, kit = Kit.WOOD), practice, seconds = 16, meanMillis = 3.0, spreadMillis = 4.0)
        assertTrue(card.withClick.hits > 4)
        assertTrue(card.alone.hits > 4)
        assertEquals(card.hits, card.withClick.hits + card.alone.hits)
    }
}
