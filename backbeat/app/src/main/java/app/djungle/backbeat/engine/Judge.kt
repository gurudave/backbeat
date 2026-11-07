package app.djungle.backbeat.engine

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How close to the click a hit landed, in the manner of an arcade rhythm game. */
enum class Grade(val label: String, val withinMillis: Float, val points: Int, val credit: Float) {
    PERFECT("perfect", 10f, 100, 1f),
    GREAT("great", 20f, 60, 0.7f),
    GOOD("good", 35f, 25, 0.4f),
    MISS("miss", Float.MAX_VALUE, 0, 0f),
    ;

    companion object {
        fun of(offsetMillis: Float): Grade = entries.first { abs(offsetMillis) <= it.withinMillis }
    }
}

/** A click as the listener hears it: when it came out of the speaker, and what it was. */
class Click(val nanos: Long, val tick: Tick)

/** One hit, judged against the nearest click. */
class Judgment(
    /** When the hit was played, by [System.nanoTime]. */
    val nanos: Long,
    /** How far from the click: below 0 is early (rushing), above is late (dragging). */
    val offsetMillis: Float,
    val grade: Grade,
    /** The click it was judged against. */
    val tick: Tick,
    /** Hits in a row without a miss, counting this one. */
    val combo: Int,
    /** Points this hit scored, with the combo's multiplier. */
    val points: Int,
)

/** How a run of hits went: their average, and how widely they spread. */
data class Spread(val hits: Int = 0, val meanMillis: Float = 0f, val spreadMillis: Float = 0f) {
    /** "rushing", "dragging" or "in the pocket". */
    val verdict: String
        get() = when {
            hits == 0 -> ""
            meanMillis < -POCKET_MILLIS -> "rushing"
            meanMillis > POCKET_MILLIS -> "dragging"
            else -> "in the pocket"
        }

    companion object {
        /** An average within this of the click is on it. */
        const val POCKET_MILLIS = 4f
    }
}

/** The end-of-game screen, as it were: how a session of playing along went. */
data class Scorecard(
    val hits: Int = 0,
    val counts: Map<Grade, Int> = emptyMap(),
    val points: Int = 0,
    val bestCombo: Int = 0,
    /** Every hit. */
    val all: Spread = Spread(),
    /** The hits played with the click sounding. */
    val withClick: Spread = Spread(),
    /** The hits played in silent bars and rests, with nothing to lean on. */
    val alone: Spread = Spread(),
    /** Average credit per hit, 0 to 1. */
    val accuracy: Float = 0f,
    val bpm: Int = 0,
) {
    fun count(grade: Grade): Int = counts[grade] ?: 0

    /** Hits within GREAT, as a share of all of them. */
    val tightness: Float get() = if (hits == 0) 0f else (count(Grade.PERFECT) + count(Grade.GREAT)).toFloat() / hits

    /** A letter, S to D, once there are enough hits to judge by. */
    val rank: String
        get() = when {
            hits < MIN_HITS -> "-"
            accuracy >= 0.9f -> "S"
            accuracy >= 0.75f -> "A"
            accuracy >= 0.6f -> "B"
            accuracy >= 0.45f -> "C"
            else -> "D"
        }

    companion object {
        /** Fewer hits than this, and a session is not ranked or kept. */
        const val MIN_HITS = 8
    }
}

/** A running mean and spread (Welford's method), so nothing needs to be kept per hit. */
private class Running {
    var n = 0
    var mean = 0.0
    var m2 = 0.0

    fun add(x: Double) {
        n++
        val d = x - mean
        mean += d / n
        m2 += d * (x - mean)
    }

    fun spread() = Spread(n, mean.toFloat(), if (n > 1) sqrt(m2 / (n - 1)).toFloat() else 0f)
}

/**
 * Judges hits against the clicks, the way an arcade rhythm game does: each one graded by how
 * near it fell, with points for it and a multiplier that grows with every hit in a row that
 * is not a miss. A hit is judged against whichever click is nearest, so it can be early for
 * the next click or late for the last.
 *
 * Rests and silent bars count: the clicks are still there, only quiet, and keeping time
 * through them is the point. Those hits are kept apart in [Scorecard.alone].
 */
class Judge(private val bpm: Int = 0) {
    private var combo = 0
    private var best = 0
    private var points = 0
    private var credit = 0.0
    private val counts = HashMap<Grade, Int>()
    private val all = Running()
    private val withClick = Running()
    private val alone = Running()

    /** Judges a hit played at [nanos] against the click [before] it and the one [after] it. */
    fun judge(nanos: Long, before: Click?, after: Click?): Judgment? {
        val click = listOfNotNull(before, after).minByOrNull { abs(it.nanos - nanos) } ?: return null
        val offset = (nanos - click.nanos) / 1e6f
        val grade = Grade.of(offset)
        combo = if (grade == Grade.MISS) 0 else combo + 1
        best = maxOf(best, combo)
        val scored = grade.points * multiplier(combo)
        points += scored
        credit += grade.credit
        counts[grade] = (counts[grade] ?: 0) + 1
        all.add(offset.toDouble())
        val heard = click.tick.audible && !(click.tick.isBeat && click.tick.accent == Accent.MUTE)
        (if (heard) withClick else alone).add(offset.toDouble())
        return Judgment(nanos, offset, grade, click.tick, combo, scored)
    }

    val card: Scorecard
        get() = Scorecard(
            hits = all.n,
            counts = counts.toMap(),
            points = points,
            bestCombo = best,
            all = all.spread(),
            withClick = withClick.spread(),
            alone = alone.spread(),
            accuracy = if (all.n == 0) 0f else (credit / all.n).toFloat(),
            bpm = bpm,
        )

    companion object {
        /** ×1, then ×2 from ten in a row, ×3 from 25, ×4 from 50. */
        fun multiplier(combo: Int): Int = when {
            combo >= 50 -> 4
            combo >= 25 -> 3
            combo >= 10 -> 2
            else -> 1
        }
    }
}

/**
 * What calibration found: how late the microphone's clock runs behind the speaker's, and how
 * loud the phone's own click is to its own microphone.
 */
data class Calibration(
    /** Add to a time read from the microphone's clock to get the speaker's. */
    val offsetNanos: Long,
    /** The click's level at the microphone, for each unit of level it was played at. */
    val echo: Float,
    /** Clicks heard, out of those played. */
    val heard: Int,
    val played: Int,
    /** How much the clicks varied, which says how far to trust the result. */
    val spreadMillis: Float,
) {
    val offsetMillis: Float get() = offsetNanos / 1e6f

    companion object {
        /**
         * Matches each click played to a hit heard soon after it. The microphone's clock may be
         * out by a lot, so "soon after" is generous, but the clicks are far enough apart that no
         * hit could be taken for the wrong click. Every click should be heard after the same
         * delay, so the delay most of them agree on is found first, and then each click is
         * matched to the hit nearest that delay, and anything far from it (a cough, a missed
         * click) is left out. Returns null unless most clicks were heard, and heard consistently.
         *
         * [clicks] are each click's time by the speaker's clock, and the level it was played at;
         * [onsets] are each hit's time by the microphone's clock, and its peak.
         */
        fun measure(clicks: List<Pair<Long, Float>>, onsets: List<Pair<Long, Float>>): Calibration? {
            val played = clicks.filter { it.second > 0f }
            if (played.isEmpty()) return null
            fun near(at: Long) = onsets.filter { it.first - at in -EARLY_NANOS..LATE_NANOS }
            val candidates = played.flatMap { (at, _) -> near(at).map { it.first - at } }
            if (candidates.isEmpty()) return null
            val agreed = candidates.sorted()[candidates.size / 2]
            val offsets = ArrayList<Long>()
            val echoes = ArrayList<Float>()
            for ((at, level) in played) {
                val hit = near(at).minByOrNull { abs(it.first - at - agreed) } ?: continue
                if (abs(hit.first - at - agreed) > AGREE_NANOS) continue
                offsets += hit.first - at
                echoes += hit.second / level
            }
            if (offsets.size < maxOf(MIN_HEARD, (played.size * 0.6).roundToInt())) return null
            val median = offsets.sorted()[offsets.size / 2]
            // The median distance from the median, scaled to read like a standard deviation.
            val mad = offsets.map { abs(it - median) }.sorted()[offsets.size / 2] * 1.4826 / 1e6
            if (mad > MAX_SPREAD_MILLIS) return null
            return Calibration(
                offsetNanos = -median,
                echo = echoes.sorted()[echoes.size / 2],
                heard = offsets.size,
                played = played.size,
                spreadMillis = mad.toFloat(),
            )
        }

        private const val EARLY_NANOS = 100_000_000L
        private const val LATE_NANOS = 350_000_000L
        private const val AGREE_NANOS = 5_000_000L
        private const val MIN_HEARD = 5
        private const val MAX_SPREAD_MILLIS = 2.5
    }
}
