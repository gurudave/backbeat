package app.djungle.backbeat.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.DayOfWeek
import java.time.LocalDate

/** The logbook at a glance. */
data class LogSummary(
    val todaySeconds: Int = 0,
    /** Days in a row with some practice, up to today (or yesterday, if today has not started yet). */
    val streak: Int = 0,
    val totalSeconds: Long = 0,
    /** The most played in a day. */
    val bestSeconds: Int = 0,
)

/**
 * How long the metronome has played, day by day, like the log a teacher asks for. Kept on the
 * phone with everything else.
 */
object Logbook {
    private val changes = MutableStateFlow(0)

    /** Goes up by one with every entry, so a screen showing the logbook knows to look again. */
    val revision: StateFlow<Int> = changes

    /** Adds [millis] of playing to [day]. A run of a few seconds, trying a sound, is not practice. */
    fun add(context: Context, millis: Long, day: LocalDate = LocalDate.now()) {
        if (millis < MIN_MILLIS) return
        synchronized(this) {
            AppSettings.setLogbook(context, record(AppSettings.logbook(context), millis, day))
        }
        changes.update { it + 1 }
    }

    /** [days] with [millis] more on [day], and nothing older than a year and a bit. */
    fun record(days: Map<String, Int>, millis: Long, day: LocalDate): Map<String, Int> {
        val key = day.toString()
        val oldest = day.minusDays(KEEP_DAYS)
        return (days + (key to (days[key] ?: 0) + (millis / 1000).toInt()))
            .filterKeys { runCatching { !LocalDate.parse(it).isBefore(oldest) }.getOrDefault(false) }
    }

    fun summarise(days: Map<String, Int>, today: LocalDate): LogSummary {
        fun on(day: LocalDate) = days[day.toString()] ?: 0
        var day = if (on(today) >= STREAK_SECONDS) today else today.minusDays(1)
        var streak = 0
        while (on(day) >= STREAK_SECONDS) {
            streak++
            day = day.minusDays(1)
        }
        return LogSummary(
            todaySeconds = on(today),
            streak = streak,
            totalSeconds = days.values.sumOf { it.toLong() },
            bestSeconds = days.values.maxOrNull() ?: 0,
        )
    }

    /**
     * The last [weeks] weeks, a column for each, Monday at the top, oldest first: the seconds
     * played each day, or null for the days still to come this week.
     */
    fun grid(days: Map<String, Int>, today: LocalDate, weeks: Int = 12): List<List<Int?>> {
        val monday = today.with(DayOfWeek.MONDAY).minusWeeks((weeks - 1).toLong())
        return List(weeks) { w ->
            List(7) { d ->
                val day = monday.plusDays(w * 7L + d)
                if (day.isAfter(today)) null else days[day.toString()] ?: 0
            }
        }
    }

    private const val MIN_MILLIS = 10_000L
    private const val KEEP_DAYS = 400L

    /** A day counts towards a streak with at least this much played. */
    const val STREAK_SECONDS = 60
}

/** The hi-score table: the eight best sessions of playing along, by points. */
object HiScores {
    const val SIZE = 8

    /** Whether [points] would make the table. */
    fun qualifies(table: List<HiScore>, points: Int): Boolean =
        points > 0 && (table.size < SIZE || points > table.last().points)

    /** The table with [entry] in its place, still eight long at most. */
    fun insert(table: List<HiScore>, entry: HiScore): List<HiScore> =
        (table + entry).sortedWith(compareByDescending<HiScore> { it.points }.thenBy { it.at }).take(SIZE)

    /** Steps letter [index] of [initials] up or down the alphabet, as an arcade cabinet does. */
    fun turn(initials: String, index: Int, by: Int): String {
        val letters = initials.padEnd(3, 'A').take(3).toCharArray()
        val at = ALPHABET.indexOf(letters[index]).coerceAtLeast(0)
        letters[index] = ALPHABET[Math.floorMod(at + by, ALPHABET.length)]
        return String(letters)
    }

    /** What a cabinet lets you write: the letters, then a few signs. */
    const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.!?*"
}
