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
 * It belongs to the audio thread. New settings arrive through [groove] and [practice].
 */
class Sequencer(private val sampleRate: Int, groove: Groove = Groove(), practice: Practice = Practice()) {

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
        }

    /** A new ramp or gap setting takes effect from the next bar. */
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

    /**
     * Moves on by [frames] frames of audio, calling [onTick] with the frame offset (from 0
     * until [frames]) of every click that falls inside them, in order.
     */
    fun advance(frames: Int, onTick: (offset: Int, tick: Tick) -> Unit) {
        val end = frame + frames
        while (true) {
            val at = maxOf(frameAt(nextTickPhase()), frame.toDouble())
            // A click due on the very edge of the block belongs to the next one.
            if (at > end - EPSILON) break
            val tick = emit(at)
            onTick(floor(at - frame + EPSILON).toInt().coerceIn(0, frames - 1), tick)
            moveOn()
        }
        frame = end
    }

    /** The click at the current position. A ramp steps the tempo on the downbeat of its bar. */
    private fun emit(at: Double): Tick {
        val p = practice
        val here = nextTickPhase()
        if (step == 0 && beatInBar == 0 && bar > 0 && p.ramp && bar % p.rampEvery.coerceAtLeast(1) == 0) {
            val next = rampedTempo(bpm, p.rampTarget, p.rampStep)
            if (next != bpm) setTempo(next, at, here)
        }
        lastTickPhase = here
        val cycle = (p.gapPlay + p.gapRest).coerceAtLeast(1)
        val audible = !p.gap || p.gapRest <= 0 || bar % cycle < p.gapPlay
        return Tick(bar, beatInBar, step, groove.accent(beatInBar), audible, bpm)
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
        val epsilon = 1e-9
        for (s in 1 until offsets.size) {
            val at = beatIndex + offsets[s]
            if (at >= phase - epsilon && at > lastTickPhase + epsilon) {
                step = s
                return
            }
        }
        nextBeat()
    }

    companion object {
        /** A millionth of a frame: rounding error, not time. */
        private const val EPSILON = 1e-6

        /** One ramp step from [bpm] towards [target], never overshooting it. */
        fun rampedTempo(bpm: Int, target: Int, step: Int): Int {
            val by = step.coerceAtLeast(1)
            return when {
                bpm < target -> (bpm + by).coerceAtMost(target)
                bpm > target -> (bpm - by).coerceAtLeast(target)
                else -> bpm
            }.coerceIn(MIN_BPM, MAX_BPM)
        }
    }
}
