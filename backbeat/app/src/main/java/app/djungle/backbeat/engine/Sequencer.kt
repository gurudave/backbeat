package app.djungle.backbeat.engine

import kotlin.math.floor

/**
 * Works out the exact audio frame of every click.
 *
 * It counts in beats. From the last change of tempo (the "anchor") the count is worked out
 * afresh from the frame number, so at a steady tempo the n-th click lands on the same frame
 * however long it has been playing, and however the audio is cut into blocks: nothing is
 * timed by sleeping, and no rounding error can build up. A new tempo re-anchors the count at
 * the frame it arrives, so it is felt at once, mid-beat.
 *
 * A cross-rhythm runs as a second stream of clicks, spread evenly across the bar it starts
 * with. It is counted in the same beats, so it keeps the same time, and it starts each bar
 * afresh on the downbeat, so it can never wander out of step with the bar.
 *
 * It belongs to the audio thread. New settings arrive through [groove] and [practice].
 * [seed] chooses which beats a dropout leaves out.
 */
class Sequencer(
    private val sampleRate: Int,
    groove: Groove = Groove(),
    practice: Practice = Practice(),
    private val seed: Long = 0L,
) {

    var groove: Groove = groove
        set(value) {
            val old = field
            field = value
            // Turning the tempo by hand takes over from a ramp.
            if (value.bpm != old.bpm) setTempo(value.bpm, frame.toDouble(), phaseAt(frame.toDouble()))
            if (value.meter != old.meter && beatInBar >= value.beats) {
                if (step == 0) {
                    beatInBar = 0
                    bar++
                } else {
                    beatInBar = value.beats - 1
                }
            }
            if (value.subdivision != old.subdivision) realign()
            if (value.meter != old.meter || value.crossCount != old.crossCount) realignCross()
        }

    /** A new ramp, gap or dropout setting takes effect from the next click. */
    var practice: Practice = practice

    /** The tempo playing now: the groove's own, unless a ramp has moved it on. */
    var bpm: Int = groove.bpm
        private set

    /** Frames handed out so far. */
    private var frame = 0L

    /** The beat count (with fractions) at the anchor frame, and how fast it rises from there. */
    private var anchorFrame = 0.0
    private var anchorPhase = 0.0
    private var beatsPerFrame = rateOf(bpm)

    /** The next click: which beat (counted from the start), and which step within it. */
    private var beatIndex = 0L
    private var step = 0

    /** Where that beat sits in the music. */
    private var bar = 0
    private var beatInBar = 0

    /** The beat count at the last click handed out. */
    private var lastTickPhase = -1.0

    /** The cross-rhythm's bar: where it starts, how many beats long it is, and its number. */
    private var crossStart = 0.0
    private var crossBeats = groove.beats
    private var crossBar = 0

    /** The next click of the cross-rhythm in that bar; once past the last, it waits for the next bar. */
    private var crossIndex = 0
    private var lastCrossPhase = -1.0

    /** Back to the top: the next [advance] starts with the downbeat of bar one. */
    fun reset() {
        frame = 0
        anchorFrame = 0.0
        anchorPhase = 0.0
        beatIndex = 0
        step = 0
        bar = 0
        beatInBar = 0
        lastTickPhase = -1.0
        bpm = groove.bpm
        beatsPerFrame = rateOf(bpm)
        crossStart = 0.0
        crossBeats = groove.beats
        crossBar = 0
        crossIndex = 0
        lastCrossPhase = -1.0
    }

    private fun rateOf(bpm: Int) = bpm / 60.0 / sampleRate

    private fun phaseAt(frame: Double) = anchorPhase + (frame - anchorFrame) * beatsPerFrame

    private fun frameAt(phase: Double) = anchorFrame + (phase - anchorPhase) / beatsPerFrame

    private fun setTempo(bpm: Int, frame: Double, phase: Double) {
        anchorFrame = frame
        anchorPhase = phase
        this.bpm = bpm
        beatsPerFrame = rateOf(bpm)
    }

    private fun nextTickPhase(): Double = beatIndex + groove.subdivision.offsets[step]

    /** Where the next click of the cross-rhythm falls, or null if there is none to come this bar. */
    private fun nextCrossPhase(): Double? {
        val n = groove.crossCount
        if (n == 0 || crossIndex >= n) return null
        return crossStart + crossIndex.toDouble() * crossBeats / n
    }

    /**
     * Moves on by [frames] frames of audio, calling [onTick] with the frame offset (from 0
     * until [frames]) of every click that falls inside them, in order.
     */
    fun advance(frames: Int, onTick: (offset: Int, tick: Tick) -> Unit) {
        val end = frame + frames
        while (true) {
            val beat = nextTickPhase()
            val cross = nextCrossPhase()
            // On a tie (the first of the bar) the beat goes first, then the cross-rhythm.
            val crossFirst = cross != null && cross < beat - PHASE_EPSILON
            val at = maxOf(frameAt(if (crossFirst) cross!! else beat), frame.toDouble())
            // A click due on the very edge of the block belongs to the next one.
            if (at > end - EPSILON) break
            val tick = if (crossFirst) emitCross(cross!!) else emit(at)
            onTick(floor(at - frame + EPSILON).toInt().coerceIn(0, frames - 1), tick)
            if (crossFirst) crossIndex++ else moveOn()
        }
        frame = end
    }

    /** Whether bar [bar] is played or left silent, by the "silent bars" practice aid. */
    private fun barAudible(bar: Int): Boolean {
        val p = practice
        val cycle = (p.gapPlay + p.gapRest).coerceAtLeast(1)
        return !p.gap || p.gapRest <= 0 || bar % cycle < p.gapPlay
    }

    /** Whether beat [index] (counted from the start) is one the dropout leaves out. */
    private fun dropped(index: Long, inBar: Int): Boolean {
        val share = practice.dropout
        if (share <= 0 || inBar == 0) return false
        return chance(index, seed) < share
    }

    /** The click at the current position. A ramp steps the tempo on the downbeat of its bar. */
    private fun emit(at: Double): Tick {
        val p = practice
        val here = nextTickPhase()
        if (step == 0 && beatInBar == 0) {
            if (bar > 0 && p.ramp && bar % p.rampEvery.coerceAtLeast(1) == 0) {
                val next = rampedTempo(bpm, p.rampTarget, p.rampStep)
                if (next != bpm) setTempo(next, at, here)
            }
            // The cross-rhythm starts every bar afresh with it.
            crossStart = here
            crossBeats = groove.beats
            crossBar = bar
            crossIndex = 0
        }
        lastTickPhase = here
        val audible = barAudible(bar) && !dropped(beatIndex, beatInBar)
        return Tick(bar, beatInBar, step, groove.accent(beatInBar), audible, bpm)
    }

    private fun emitCross(phase: Double): Tick {
        val n = groove.crossCount
        lastCrossPhase = phase
        val beat = floor(crossIndex.toDouble() * crossBeats / n + PHASE_EPSILON).toInt()
        val accent = if (crossIndex == 0) Accent.STRONG else Accent.NORMAL
        return Tick(crossBar, beat, 0, accent, barAudible(crossBar), bpm, cross = crossIndex)
    }

    private fun moveOn() {
        step++
        if (step >= groove.subdivision.steps) nextBeat()
    }

    private fun nextBeat() {
        step = 0
        beatIndex++
        beatInBar++
        if (beatInBar >= groove.beats) {
            beatInBar = 0
            bar++
        }
    }

    /**
     * After the subdivision changes mid-beat, finds the first click of the new pattern still
     * to come in this beat. With none left, it waits for the next beat.
     */
    private fun realign() {
        if (step == 0) return
        val offsets = groove.subdivision.offsets
        val phase = phaseAt(frame.toDouble())
        for (s in 1 until offsets.size) {
            val at = beatIndex + offsets[s]
            if (at >= phase - PHASE_EPSILON && at > lastTickPhase + PHASE_EPSILON) {
                step = s
                return
            }
        }
        nextBeat()
    }

    /**
     * After the meter or the cross-rhythm changes mid-bar, spreads the cross-rhythm over the
     * bar as it now is, and carries on from its first click still to come.
     */
    private fun realignCross() {
        val n = groove.crossCount
        crossStart = (beatIndex - beatInBar).toDouble()
        crossBeats = groove.beats
        crossBar = bar
        val now = phaseAt(frame.toDouble())
        crossIndex = (0 until n).firstOrNull { k ->
            val at = crossStart + k.toDouble() * crossBeats / n
            at >= now - PHASE_EPSILON && at > lastCrossPhase + PHASE_EPSILON
        } ?: n
    }

    companion object {
        /** A millionth of a frame: rounding error, not time. */
        private const val EPSILON = 1e-6

        /** The same, counted in beats. */
        private const val PHASE_EPSILON = 1e-9

        /** One ramp step from [bpm] towards [target], never overshooting it. */
        fun rampedTempo(bpm: Int, target: Int, step: Int): Int {
            val by = step.coerceAtLeast(1)
            return when {
                bpm < target -> (bpm + by).coerceAtMost(target)
                bpm > target -> (bpm - by).coerceAtLeast(target)
                else -> bpm
            }.coerceIn(MIN_BPM, MAX_BPM)
        }

        /** A number from 0 to 99 for beat [index], the same every time for the same [seed]. */
        fun chance(index: Long, seed: Long): Int {
            var z = index * -0x61c8864680b583ebL + seed
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            z = z xor (z ushr 31)
            return ((z ushr 1) % 100).toInt()
        }
    }
}
