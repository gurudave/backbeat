package app.djungle.backbeat.data

import android.content.Context
import android.content.SharedPreferences
import app.djungle.backbeat.engine.Accent
import app.djungle.backbeat.engine.Calibration
import app.djungle.backbeat.engine.Groove
import app.djungle.backbeat.engine.Kit
import app.djungle.backbeat.engine.MAX_BPM
import app.djungle.backbeat.engine.MIN_BPM
import app.djungle.backbeat.engine.Meter
import app.djungle.backbeat.engine.Practice
import app.djungle.backbeat.engine.Subdivision
import app.djungle.backbeat.ui.theme.Vibe
import org.json.JSONArray
import org.json.JSONObject

/** The switches on the SET UP tab. */
data class Options(
    /** The picture on the screen blinks on the one. */
    val flash: Boolean = false,
    /** The phone taps along on every beat. */
    val buzz: Boolean = false,
    /** Carry on with the screen off or in another app. */
    val keepTicking: Boolean = false,
    /** The Ear listens while the metronome plays, and judges every hit. */
    val coach: Boolean = false,
)

/** A saved set-up, shown as a cassette on the TAPES tab. */
data class Tape(val id: Long, val name: String, val groove: Groove)

/** A line of the hi-score table. */
data class HiScore(
    val initials: String,
    val points: Int,
    val rank: String,
    /** Hits within GREAT, as a percentage. */
    val tight: Int,
    val bpm: Int,
    /** When, in milliseconds since 1970. */
    val at: Long,
)

/** Everything is remembered on the phone, nowhere else. */
object AppSettings {
    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("backbeat_settings", Context.MODE_PRIVATE)

    private inline fun <reified T : Enum<T>> SharedPreferences.choice(key: String, default: T): T {
        val saved = getString(key, null)
        return enumValues<T>().firstOrNull { it.name == saved } ?: default
    }

    fun vibe(context: Context): Vibe = prefs(context).choice("vibe", Vibe.HIGH_SCORE)

    fun setVibe(context: Context, vibe: Vibe) = prefs(context).edit().putString("vibe", vibe.name).apply()

    fun groove(context: Context): Groove = prefs(context).run {
        val meter = choice("meter", Meter.FOUR_FOUR)
        val accents = getString("accents", null)?.let(Accent::parse)?.takeIf { it.size == meter.beats } ?: meter.accents
        Groove(
            bpm = getInt("bpm", 100).coerceIn(MIN_BPM, MAX_BPM),
            meter = meter,
            accents = accents,
            subdivision = choice("subdivision", Subdivision.NONE),
            kit = choice("kit", Kit.BLIP),
            volume = getFloat("volume", 0.8f).coerceIn(0.1f, 1f),
            cross = getInt("cross", 0).coerceIn(0, MAX_CROSS),
        )
    }

    fun setGroove(context: Context, groove: Groove) = prefs(context).edit()
        .putInt("bpm", groove.bpm)
        .putString("meter", groove.meter.name)
        .putString("accents", Accent.encode(groove.accents))
        .putString("subdivision", groove.subdivision.name)
        .putString("kit", groove.kit.name)
        .putFloat("volume", groove.volume)
        .putInt("cross", groove.cross)
        .apply()

    fun practice(context: Context): Practice = prefs(context).run {
        Practice(
            ramp = getBoolean("ramp", false),
            rampStep = getInt("ramp_step", 2),
            rampEvery = getInt("ramp_every", 4),
            rampTarget = getInt("ramp_target", 140),
            gap = getBoolean("gap", false),
            gapPlay = getInt("gap_play", 2),
            gapRest = getInt("gap_rest", 2),
            stopAfterMinutes = getInt("stop_after", 0),
            dropout = getInt("dropout", 0).coerceIn(0, 90),
        )
    }

    fun setPractice(context: Context, practice: Practice) = prefs(context).edit()
        .putBoolean("ramp", practice.ramp)
        .putInt("ramp_step", practice.rampStep)
        .putInt("ramp_every", practice.rampEvery)
        .putInt("ramp_target", practice.rampTarget)
        .putBoolean("gap", practice.gap)
        .putInt("gap_play", practice.gapPlay)
        .putInt("gap_rest", practice.gapRest)
        .putInt("stop_after", practice.stopAfterMinutes)
        .putInt("dropout", practice.dropout)
        .apply()

    fun options(context: Context): Options = prefs(context).run {
        Options(
            flash = getBoolean("flash", false),
            buzz = getBoolean("buzz", false),
            keepTicking = getBoolean("keep_ticking", false),
            coach = getBoolean("coach", false),
        )
    }

    fun setOptions(context: Context, options: Options) = prefs(context).edit()
        .putBoolean("flash", options.flash)
        .putBoolean("buzz", options.buzz)
        .putBoolean("keep_ticking", options.keepTicking)
        .putBoolean("coach", options.coach)
        .apply()

    fun keepTicking(context: Context): Boolean = prefs(context).getBoolean("keep_ticking", false)

    // ---- tapes ----

    fun tapes(context: Context): List<Tape> {
        val json = prefs(context).getString("tapes", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i -> tapeFrom(array.getJSONObject(i)) }
        }.getOrDefault(emptyList())
    }

    fun setTapes(context: Context, tapes: List<Tape>) {
        val array = JSONArray()
        tapes.forEach { array.put(tapeJson(it)) }
        prefs(context).edit().putString("tapes", array.toString()).apply()
    }

    private fun tapeJson(tape: Tape) = JSONObject()
        .put("id", tape.id)
        .put("name", tape.name)
        .put("bpm", tape.groove.bpm)
        .put("meter", tape.groove.meter.name)
        .put("accents", Accent.encode(tape.groove.accents))
        .put("subdivision", tape.groove.subdivision.name)
        .put("kit", tape.groove.kit.name)
        .put("volume", tape.groove.volume.toDouble())
        .put("cross", tape.groove.cross)

    private fun tapeFrom(json: JSONObject): Tape? {
        val meter = Meter.entries.firstOrNull { it.name == json.optString("meter") } ?: return null
        val accents = Accent.parse(json.optString("accents")).takeIf { it.size == meter.beats } ?: meter.accents
        return Tape(
            id = json.optLong("id"),
            name = json.optString("name", "tape"),
            groove = Groove(
                bpm = json.optInt("bpm", 100).coerceIn(MIN_BPM, MAX_BPM),
                meter = meter,
                accents = accents,
                subdivision = Subdivision.entries.firstOrNull { it.name == json.optString("subdivision") } ?: Subdivision.NONE,
                kit = Kit.entries.firstOrNull { it.name == json.optString("kit") } ?: Kit.BLIP,
                volume = json.optDouble("volume", 0.8).toFloat().coerceIn(0.1f, 1f),
                cross = json.optInt("cross", 0).coerceIn(0, MAX_CROSS),
            ),
        )
    }

    /** The most clicks a cross-rhythm may spread across a bar. */
    const val MAX_CROSS = 9

    // ---- the Ear ----

    fun calibration(context: Context): Calibration? = prefs(context).run {
        if (!contains("cal_offset")) return null
        Calibration(
            offsetNanos = getLong("cal_offset", 0L),
            echo = getFloat("cal_echo", 0.5f),
            heard = getInt("cal_heard", 0),
            played = getInt("cal_played", 0),
            spreadMillis = getFloat("cal_spread", 0f),
        )
    }

    fun setCalibration(context: Context, calibration: Calibration) = prefs(context).edit()
        .putLong("cal_offset", calibration.offsetNanos)
        .putFloat("cal_echo", calibration.echo)
        .putInt("cal_heard", calibration.heard)
        .putInt("cal_played", calibration.played)
        .putFloat("cal_spread", calibration.spreadMillis)
        .apply()

    /** The hand correction to the Ear's timing, in milliseconds. */
    fun nudge(context: Context): Int = prefs(context).getInt("nudge", 0)

    fun setNudge(context: Context, millis: Int) = prefs(context).edit().putInt("nudge", millis).apply()

    /** The hi-score table, best first. */
    fun hiScores(context: Context): List<HiScore> {
        val json = prefs(context).getString("hi_scores", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                HiScore(
                    initials = o.optString("initials", "???").take(3),
                    points = o.optInt("points"),
                    rank = o.optString("rank", "-"),
                    tight = o.optInt("tight"),
                    bpm = o.optInt("bpm"),
                    at = o.optLong("at"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun setHiScores(context: Context, scores: List<HiScore>) {
        val array = JSONArray()
        scores.forEach {
            array.put(
                JSONObject()
                    .put("initials", it.initials)
                    .put("points", it.points)
                    .put("rank", it.rank)
                    .put("tight", it.tight)
                    .put("bpm", it.bpm)
                    .put("at", it.at),
            )
        }
        prefs(context).edit().putString("hi_scores", array.toString()).apply()
    }

    /** The initials last written on the table, to offer again. */
    fun lastInitials(context: Context): String = prefs(context).getString("initials", "AAA") ?: "AAA"

    fun setLastInitials(context: Context, initials: String) = prefs(context).edit().putString("initials", initials).apply()

    // ---- the logbook ----

    /** Seconds played on each day, by its ISO date (2026-11-07). */
    fun logbook(context: Context): Map<String, Int> {
        val json = prefs(context).getString("logbook", null) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(json)
            o.keys().asSequence().associateWith { o.optInt(it) }
        }.getOrDefault(emptyMap())
    }

    fun setLogbook(context: Context, days: Map<String, Int>) {
        val o = JSONObject()
        days.forEach { (day, seconds) -> o.put(day, seconds) }
        prefs(context).edit().putString("logbook", o.toString()).apply()
    }
}
