package app.djungle.backbeat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RecordsTest {
    private val today = LocalDate.of(2026, 11, 7)

    @Test
    fun playingAddsUpByTheDay() {
        var days = emptyMap<String, Int>()
        days = Logbook.record(days, 90_000, today)
        days = Logbook.record(days, 30_500, today)
        days = Logbook.record(days, 600_000, today.minusDays(1))
        assertEquals(mapOf("2026-11-07" to 120, "2026-11-06" to 600), days)
        assertEquals(LogSummary(todaySeconds = 120, streak = 2, totalSeconds = 720, bestSeconds = 600), Logbook.summarise(days, today))
    }

    @Test
    fun aYearAndABitIsKept() {
        val days = Logbook.record(mapOf("2025-01-01" to 50, "2026-01-01" to 70), 60_000, today)
        assertEquals(setOf("2026-01-01", "2026-11-07"), days.keys)
    }

    @Test
    fun aStreakSurvivesUntilTheDayIsOut() {
        val days = mapOf("2026-11-06" to 300, "2026-11-05" to 61, "2026-11-04" to 59, "2026-11-03" to 900)
        // Nothing yet today: the streak still stands on yesterday and the day before.
        assertEquals(2, Logbook.summarise(days, today).streak)
        // Once today is played, it grows; a day missed ends it.
        assertEquals(3, Logbook.summarise(days + ("2026-11-07" to 60), today).streak)
        assertEquals(0, Logbook.summarise(days, today.plusDays(2)).streak)
    }

    @Test
    fun theGridHasAColumnAWeekMondayFirst() {
        // 7 November 2026 is a Saturday.
        val grid = Logbook.grid(mapOf("2026-11-07" to 42, "2026-11-02" to 7, "2026-08-17" to 3), today, weeks = 12)
        assertEquals(12, grid.size)
        assertTrue(grid.all { it.size == 7 })
        val thisWeek = grid.last()
        assertEquals(listOf(7, 0, 0, 0, 0, 42, null), thisWeek)
        // Twelve weeks back starts on Monday 17 August.
        assertEquals(3, grid.first()[0])
        assertNull(grid.dropLast(1).flatten().firstOrNull { it == null })
    }

    @Test
    fun theHiScoreTableKeepsTheBestEight() {
        fun score(points: Int, at: Long = 0) = HiScore("ABC", points, "A", 90, 120, at)
        var table = emptyList<HiScore>()
        (1..8).forEach { table = HiScores.insert(table, score(it * 100)) }
        assertEquals(800, table.first().points)
        assertFalse(HiScores.qualifies(table, 100))
        assertTrue(HiScores.qualifies(table, 150))
        assertFalse(HiScores.qualifies(emptyList(), 0))
        table = HiScores.insert(table, score(150))
        assertEquals(8, table.size)
        assertEquals(150, table.last().points)
        // A tie goes to whoever got there first.
        table = HiScores.insert(table, score(800, at = 5))
        assertEquals(listOf(0L, 5L), table.take(2).map { it.at })
    }

    @Test
    fun initialsTurnRoundTheAlphabet() {
        assertEquals("BAA", HiScores.turn("AAA", 0, 1))
        assertEquals("A*A", HiScores.turn("AAA", 1, -1))
        assertEquals("AAA", HiScores.turn("A*A", 1, 1))
        assertEquals("ZZB", HiScores.turn("ZZ", 2, 1))
    }
}
