package app.djungle.backbeat.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JudgeTest {
    private val ms = 1_000_000L

    private fun click(at: Long, beat: Int = 0, audible: Boolean = true, accent: Accent = Accent.NORMAL) =
        Click(at * ms, Tick(0, beat, 0, accent, audible, 120))

    @Test
    fun gradesGoByMilliseconds() {
        assertEquals(Grade.PERFECT, Grade.of(0f))
        assertEquals(Grade.PERFECT, Grade.of(-10f))
        assertEquals(Grade.GREAT, Grade.of(15f))
        assertEquals(Grade.GOOD, Grade.of(-30f))
        assertEquals(Grade.MISS, Grade.of(36f))
    }

    @Test
    fun aHitIsJudgedAgainstTheNearestClick() {
        val judge = Judge()
        val early = judge.judge(480 * ms, click(0), click(500))!!
        assertEquals(-20f, early.offsetMillis, 1e-3f)
        val late = judge.judge(30 * ms, click(0), click(500))!!
        assertEquals(30f, late.offsetMillis, 1e-3f)
        assertNull(judge.judge(30 * ms, null, null))
    }

    @Test
    fun theComboGrowsTheMultiplierAndAMissEndsIt() {
        val judge = Judge()
        var last: Judgment? = null
        repeat(12) { last = judge.judge(it * 500 * ms + 2 * ms, click(it * 500L), null) }
        assertEquals(12, last!!.combo)
        // From the tenth hit in a row, perfects are worth double.
        assertEquals(200, last!!.points)
        assertEquals(9 * 100 + 3 * 200, judge.card.points)
        val miss = judge.judge(6_000 * ms + 80 * ms, click(6_000), null)!!
        assertEquals(Grade.MISS, miss.grade)
        assertEquals(0, miss.combo)
        assertEquals(12, judge.card.bestCombo)
        assertEquals(listOf(1, 1, 2, 2, 3, 3, 4), listOf(0, 9, 10, 24, 25, 49, 50).map(Judge::multiplier))
    }

    @Test
    fun theCardSaysWhetherYouRushOrDrag() {
        val dragging = Judge()
        repeat(16) { dragging.judge(it * 500 * ms + 12 * ms + (it % 3) * ms, click(it * 500L), null) }
        assertEquals("dragging", dragging.card.all.verdict)
        assertEquals(13f, dragging.card.all.meanMillis, 0.5f)
        assertTrue(dragging.card.all.spreadMillis in 0.5f..1.5f)

        val rushing = Judge()
        repeat(16) { rushing.judge(it * 500 * ms - 9 * ms, click(it * 500L), null) }
        assertEquals("rushing", rushing.card.all.verdict)

        val tight = Judge()
        repeat(16) { tight.judge(it * 500 * ms + (if (it % 2 == 0) 2 else -2) * ms, click(it * 500L), null) }
        assertEquals("in the pocket", tight.card.all.verdict)
        assertEquals("S", tight.card.rank)
        assertEquals(1f, tight.card.tightness, 0f)
    }

    @Test
    fun hitsInTheSilenceAreKeptApart() {
        val judge = Judge()
        repeat(8) { judge.judge(it * 500 * ms + 1 * ms, click(it * 500L), null) }
        repeat(8) { judge.judge((4_000 + it * 500) * ms + 25 * ms, click(4_000L + it * 500, audible = false), null) }
        // A rest is a silence too.
        judge.judge(9_000 * ms + 25 * ms, click(9_000, accent = Accent.MUTE), null)
        val card = judge.card
        assertEquals(17, card.hits)
        assertEquals(8, card.withClick.hits)
        assertEquals(9, card.alone.hits)
        assertEquals("in the pocket", card.withClick.verdict)
        assertEquals("dragging", card.alone.verdict)
    }

    @Test
    fun tooFewHitsAreNotRanked() {
        val judge = Judge()
        repeat(Scorecard.MIN_HITS - 1) { judge.judge(it * 500 * ms, click(it * 500L), null) }
        assertEquals("-", judge.card.rank)
    }

    @Test
    fun calibrationFindsHowLateTheMicrophoneIs() {
        // Clicks every half second at full level, heard 37 ms late give or take a little,
        // with two missed and a cough in between.
        val clicks = (0 until 10).map { it * 500 * ms to 1f }
        val onsets = clicks.mapIndexedNotNull { n, (at, _) ->
            if (n == 3 || n == 7) null else at + 37 * ms + listOf(-300_000L, 0L, 250_000L)[n % 3] to 0.2f
        } + (1_700 * ms to 0.6f)
        val calibration = Calibration.measure(clicks, onsets.sortedBy { it.first })!!
        assertEquals(-37f, calibration.offsetMillis, 0.3f)
        assertEquals(8, calibration.heard)
        assertEquals(10, calibration.played)
        assertEquals(0.2f, calibration.echo, 1e-6f)
    }

    @Test
    fun calibrationGivesUpWhenItCannotHearTheClicks() {
        val clicks = (0 until 10).map { it * 500 * ms to 1f }
        assertNull(Calibration.measure(clicks, listOf(37 * ms to 0.2f, 537 * ms to 0.2f)))
        // Heard, but all over the place.
        val wild = clicks.mapIndexed { n, (at, _) -> at + (20 + n * 9) * ms to 0.2f }
        assertNull(Calibration.measure(clicks, wild))
    }
}
