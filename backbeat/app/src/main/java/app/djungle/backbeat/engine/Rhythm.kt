package app.djungle.backbeat.engine

/** Tempos the metronome will play, in beats per minute. */
const val MIN_BPM = 20
const val MAX_BPM = 300

/** How loudly a beat is played. Tapping a beat light steps through these in order. */
enum class Accent(val code: Char, val label: String) {
    /** The high click, for the one. */
    STRONG('A', "accent"),

    /** An ordinary beat. */
    NORMAL('N', "beat"),

    /** A ghost: the ordinary click, played quietly. */
    SOFT('S', "ghost"),

    /** A rest: nothing at all, though the lights carry on. */
    MUTE('M', "rest"),
    ;

    fun next(): Accent = entries[(ordinal + 1) % entries.size]

    companion object {
        fun of(code: Char): Accent = entries.firstOrNull { it.code == code } ?: NORMAL

        fun parse(codes: String): List<Accent> = codes.map(::of)

        fun encode(accents: List<Accent>): String = accents.joinToString("") { it.code.toString() }
    }
}

/**
 * The time signatures on offer. Each beat is one of the bottom note: 6/8 at 120 BPM is
 * 120 quavers a minute, which is how most players set it. [pattern] holds the accents each
 * one starts with, written as [Accent] codes, so 6/8 counts ONE-two-three-FOUR-five-six.
 */
enum class Meter(val beats: Int, val unit: Int, val pattern: String) {
    ONE_FOUR(1, 4, "N"),
    TWO_FOUR(2, 4, "AN"),
    THREE_FOUR(3, 4, "ANN"),
    FOUR_FOUR(4, 4, "ANNN"),
    FIVE_FOUR(5, 4, "ANNNN"),
    SIX_FOUR(6, 4, "ANNNNN"),
    SEVEN_FOUR(7, 4, "ANNNNNN"),
    THREE_EIGHT(3, 8, "ASS"),
    FIVE_EIGHT(5, 8, "ASSNS"),
    SIX_EIGHT(6, 8, "ASSNSS"),
    SEVEN_EIGHT(7, 8, "ASNSNSS"),
    NINE_EIGHT(9, 8, "ASSNSSNSS"),
    TWELVE_EIGHT(12, 8, "ASSNSSNSSNSS"),
    ;

    val label: String get() = "$beats/$unit"

    val accents: List<Accent> get() = Accent.parse(pattern)
}

/**
 * How each beat is split. [offsets] says where each click falls, as a fraction of the beat.
 * Swing is a pair of quavers with the second one late, on the last third of the beat.
 */
enum class Subdivision(val label: String, val offsets: DoubleArray) {
    NONE("none", doubleArrayOf(0.0)),
    EIGHTHS("8ths", doubleArrayOf(0.0, 0.5)),
    TRIPLETS("triplets", doubleArrayOf(0.0, 1.0 / 3.0, 2.0 / 3.0)),
    SIXTEENTHS("16ths", doubleArrayOf(0.0, 0.25, 0.5, 0.75)),
    SWING("swing", doubleArrayOf(0.0, 2.0 / 3.0)),
    ;

    val steps: Int get() = offsets.size
}

/** Everything about what the metronome plays. */
data class Groove(
    val bpm: Int = 100,
    val meter: Meter = Meter.FOUR_FOUR,
    val accents: List<Accent> = meter.accents,
    val subdivision: Subdivision = Subdivision.NONE,
    val kit: Kit = Kit.BLIP,
    /** 0.1 to 1. */
    val volume: Float = 0.8f,
) {
    val beats: Int get() = meter.beats

    /** The accent of beat [index] (from 0), even if the pattern and the meter disagree. */
    fun accent(index: Int): Accent = accents.getOrElse(index) { Accent.NORMAL }

    fun withMeter(meter: Meter) = copy(meter = meter, accents = meter.accents)

    fun withBpm(bpm: Int) = copy(bpm = bpm.coerceIn(MIN_BPM, MAX_BPM))

    /** Steps beat [index] on to its next accent. */
    fun cycleAccent(index: Int): Groove {
        val padded = List(beats) { accent(it) }
        return copy(accents = padded.mapIndexed { i, a -> if (i == index) a.next() else a })
    }
}

/** The practice aids: a tempo that climbs, bars that drop out, and a timer. */
data class Practice(
    val ramp: Boolean = false,
    /** BPM added (or taken away, if the target is slower) each time. */
    val rampStep: Int = 2,
    /** Every this many bars. */
    val rampEvery: Int = 4,
    val rampTarget: Int = 140,
    val gap: Boolean = false,
    /** Bars played... */
    val gapPlay: Int = 2,
    /** ...then bars left silent for the player to keep time alone. */
    val gapRest: Int = 2,
    /** Stop at the first downbeat after this many minutes; 0 means never. */
    val stopAfterMinutes: Int = 0,
)

/** One click, as the sequencer hands it out. [step] is 0 on the beat and 1+ for subdivisions. */
data class Tick(
    val bar: Int,
    val beat: Int,
    val step: Int,
    val accent: Accent,
    /** False inside a silent bar: the lights still move, but nothing is heard. */
    val audible: Boolean,
    /** The tempo at this tick, which a ramp may have moved away from the groove's. */
    val bpm: Int,
) {
    val isDownbeat: Boolean get() = beat == 0 && step == 0
}

/** The traditional Italian name for a tempo, for the display. */
fun tempoMarking(bpm: Int): String = when {
    bpm < 40 -> "grave"
    bpm < 60 -> "largo"
    bpm < 66 -> "larghetto"
    bpm < 76 -> "adagio"
    bpm < 108 -> "andante"
    bpm < 120 -> "moderato"
    bpm < 156 -> "allegro"
    bpm < 176 -> "vivace"
    bpm < 200 -> "presto"
    else -> "prestissimo"
}
