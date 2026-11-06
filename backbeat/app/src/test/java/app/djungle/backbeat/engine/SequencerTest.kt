package app.djungle.backbeat.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToLong

class SequencerTest {
    private val rate = 48_000

    /** Runs [sequencer] for [frames] frames in blocks of [block], returning (frame, tick) pairs. */
    private fun run(sequencer: Sequencer, frames: Long, block: Int = 240, from: Long = 0): List<Pair<Long, Tick>> {
        val out = ArrayList<Pair<Long, Tick>>()
        var at = from
        while (at < from + frames) {
            val n = minOf(block.toLong(), from + frames - at).toInt()
            val start = at
            sequencer.advance(n) { offset, tick -> out += (start + offset) to tick }
            at += n
        }
        return out
    }

    @Test
    fun crotchetsLandOnExactFrames() {
        val ticks = run(Sequencer(rate, Groove(bpm = 120)), rate * 10L)
        assertEquals(20, ticks.size)
        ticks.forEachIndexed { n, (frame, _) -> assertEquals(n * 24_000L, frame) }
    }

    @Test
    fun theFirstClickIsTheDownbeatOfBarOne() {
        val (frame, tick) = run(Sequencer(rate, Groove(bpm = 90)), 10).single()
        assertEquals(0L, frame)
        assertTrue(tick.isDownbeat)
        assertEquals(0, tick.bar)
        assertEquals(Accent.STRONG, tick.accent)
    }

    @Test
    fun barsCountRoundTheMeterWithItsAccents() {
        val ticks = run(Sequencer(rate, Groove(bpm = 120).withMeter(Meter.THREE_FOUR)), rate * 3L).map { it.second }
        assertEquals(listOf(0, 1, 2, 0, 1, 2), ticks.map { it.beat })
        assertEquals(listOf(0, 0, 0, 1, 1, 1), ticks.map { it.bar })
        assertEquals(listOf(Accent.STRONG, Accent.NORMAL, Accent.NORMAL), ticks.take(3).map { it.accent })
    }

    @Test
    fun compoundMetersStartWithTheirOwnAccents() {
        assertEquals(
            listOf(Accent.STRONG, Accent.SOFT, Accent.SOFT, Accent.NORMAL, Accent.SOFT, Accent.SOFT),
            Meter.SIX_EIGHT.accents,
        )
        Meter.entries.forEach { assertEquals(it.label, it.beats, it.accents.size) }
    }

    @Test
    fun tripletsSplitTheBeatInThree() {
        val ticks = run(Sequencer(rate, Groove(bpm = 60, subdivision = Subdivision.TRIPLETS)), rate * 2L)
        assertEquals(listOf(0L, 16_000L, 32_000L, 48_000L, 64_000L, 80_000L), ticks.map { it.first })
        assertEquals(listOf(0, 1, 2, 0, 1, 2), ticks.map { it.second.step })
        assertEquals(listOf(0, 0, 0, 1, 1, 1), ticks.map { it.second.beat })
    }

    @Test
    fun swingPutsTheOffbeatOnTheLastThird() {
        val ticks = run(Sequencer(rate, Groove(bpm = 60, subdivision = Subdivision.SWING)), rate * 2L)
        assertEquals(listOf(0L, 32_000L, 48_000L, 80_000L), ticks.map { it.first })
    }

    @Test
    fun theBlockSizeMakesNoDifference() {
        val groove = Groove(bpm = 97, subdivision = Subdivision.TRIPLETS, meter = Meter.SEVEN_EIGHT)
        val reference = run(Sequencer(rate, groove), rate * 20L, block = 64)
        for (block in listOf(1, 240, 1000, 4096)) {
            assertEquals("block $block", reference, run(Sequencer(rate, groove), rate * 20L, block = block))
        }
    }

    @Test
    fun anHourAtAnAwkwardTempoDoesNotDrift() {
        val bpm = 133
        val ticks = run(Sequencer(rate, Groove(bpm = bpm)), rate * 3600L, block = 4800)
        assertEquals(bpm * 60, ticks.size)
        ticks.forEachIndexed { n, (frame, _) ->
            val ideal = (n * 60.0 / bpm * rate).roundToLong()
            assertTrue("beat $n at $frame, should be $ideal", abs(frame - ideal) <= 1)
        }
    }

    @Test
    fun aNewTempoIsFeltMidBeat() {
        val sequencer = Sequencer(rate, Groove(bpm = 60))
        assertEquals(listOf(0L), run(sequencer, 24_000).map { it.first })
        // Halfway through a one-second beat, double the tempo: the rest of it takes 0.25 s.
        sequencer.groove = sequencer.groove.withBpm(120)
        val next = run(sequencer, 48_000, from = 24_000).map { it.first }
        assertEquals(listOf(36_000L, 60_000L), next)
    }

    @Test
    fun aShorterBarStartsAfreshOnTheNextBeat() {
        val sequencer = Sequencer(rate, Groove(bpm = 120))
        // Beats 1, 2 and 3 of a 4/4 bar, with beat 4 to come.
        assertEquals(3, run(sequencer, 3 * 24_000L - 10).size)
        sequencer.groove = sequencer.groove.withMeter(Meter.THREE_FOUR)
        val tick = run(sequencer, 20, from = 3 * 24_000L - 10).single().second
        assertTrue(tick.isDownbeat)
        assertEquals(1, tick.bar)
    }

    @Test
    fun aNewSubdivisionPicksUpWithinTheBeat() {
        val sequencer = Sequencer(rate, Groove(bpm = 60, subdivision = Subdivision.SIXTEENTHS))
        assertEquals(listOf(0L, 12_000L), run(sequencer, 13_000).map { it.first })
        sequencer.groove = sequencer.groove.copy(subdivision = Subdivision.TRIPLETS)
        val next = run(sequencer, 48_000, from = 13_000)
        assertEquals(listOf(16_000L, 32_000L, 48_000L), next.map { it.first })
        assertEquals(listOf(1, 2, 0), next.map { it.second.step })
    }

    @Test
    fun aRampClimbsOnTheDownbeatAndStopsAtTheTarget() {
        val practice = Practice(ramp = true, rampStep = 5, rampEvery = 2, rampTarget = 110)
        val ticks = run(Sequencer(rate, Groove(bpm = 100), practice), rate * 20L)
        val downbeats = ticks.filter { it.second.isDownbeat }
        assertEquals(listOf(100, 100, 105, 105, 110, 110), downbeats.take(6).map { it.second.bpm })
        assertTrue(ticks.all { it.second.bpm <= 110 })
        // Bars one and two are played entirely at the old tempo.
        val beat = 60.0 / 100 * rate
        assertEquals((8 * beat).roundToLong(), downbeats[2].first)
    }

    @Test
    fun aRampCanComeDownToo() {
        assertEquals(97, Sequencer.rampedTempo(100, 90, 3))
        assertEquals(90, Sequencer.rampedTempo(92, 90, 3))
        assertEquals(90, Sequencer.rampedTempo(90, 90, 3))
    }

    @Test
    fun turningTheTempoByHandTakesOverFromARamp() {
        val sequencer = Sequencer(rate, Groove(bpm = 100), Practice(ramp = true, rampStep = 10, rampEvery = 1, rampTarget = 200))
        run(sequencer, rate * 5L)
        assertTrue(sequencer.bpm > 100)
        sequencer.groove = sequencer.groove.withBpm(80)
        assertEquals(80, sequencer.bpm)
    }

    @Test
    fun silentBarsGoQuietButKeepCounting() {
        val practice = Practice(gap = true, gapPlay = 1, gapRest = 1)
        val ticks = run(Sequencer(rate, Groove(bpm = 240), practice), rate * 3L).map { it.second }
        assertEquals((0..2).toList(), ticks.map { it.bar }.distinct())
        ticks.forEach { assertEquals("bar ${it.bar}", it.bar % 2 == 0, it.audible) }
    }

    @Test
    fun cyclingAnAccentGoesRoundAllFour() {
        var groove = Groove()
        assertEquals(Accent.STRONG, groove.accent(0))
        groove = groove.cycleAccent(0)
        assertEquals(Accent.NORMAL, groove.accent(0))
        groove = groove.cycleAccent(0).cycleAccent(0).cycleAccent(0)
        assertEquals(Accent.STRONG, groove.accent(0))
        assertFalse(groove.cycleAccent(2).accent(2) == Accent.NORMAL)
        assertEquals("ANNN", Accent.encode(groove.accents))
        assertEquals(groove.accents, Accent.parse("ANNN"))
    }

    @Test
    fun tempoMarkingsRunSlowToFast() {
        assertEquals("grave", tempoMarking(30))
        assertEquals("andante", tempoMarking(90))
        assertEquals("allegro", tempoMarking(120))
        assertEquals("prestissimo", tempoMarking(MAX_BPM))
    }
}
