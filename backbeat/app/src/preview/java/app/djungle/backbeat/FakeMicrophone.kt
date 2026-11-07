package app.djungle.backbeat

import app.djungle.backbeat.engine.Ear
import app.djungle.backbeat.engine.EarMode
import app.djungle.backbeat.engine.InputStamp
import app.djungle.backbeat.engine.Kit
import app.djungle.backbeat.engine.Metronome
import app.djungle.backbeat.engine.Microphone
import app.djungle.backbeat.engine.Placed
import app.djungle.backbeat.engine.Synth
import kotlin.math.exp
import kotlin.random.Random

/**
 * A stand-in for the microphone on the CI emulator, which has none worth the name.
 *
 * It hears what a phone on a music stand would. The metronome's own click arrives a little
 * after it is played, quieter, as it would through the air. While the Ear judges, a player
 * claps along with every beat, a touch late and a little loose (the report says by how much,
 * to check the Ear finds the same). With the metronome stopped, a drum loop plays at
 * [LOOP_BPM], for the tempo finder.
 *
 * Its clock is deliberately wrong, as a real phone's can be, so calibration has something to
 * find.
 */
class FakeMicrophone : Microphone {
    private var rate = 48_000
    private var started = 0L
    private var frames = 0L
    private var lastPlaced = -1L
    private val random = Random(7)

    /** Sounds due in the input: the frame each starts on, its samples, and how loud. */
    private class Sound(val frame: Long, val samples: FloatArray, val gain: Float)

    private val due = ArrayList<Sound>()
    private var loopBeat = 0L

    /** Clicks placed but not yet sounding, and when the player means to clap for each. */
    private class Coming(val placed: Placed, val clapMillis: Double?)

    private val coming = ArrayList<Coming>()

    private lateinit var drums: List<FloatArray>
    private lateinit var claps: List<FloatArray>
    private val clicks = HashMap<Long, FloatArray>()

    override fun open(sampleRate: Int): Boolean {
        rate = sampleRate
        started = System.nanoTime()
        frames = 0
        val kit = Synth.render(Kit.DRUMS, sampleRate)
        drums = listOf(kit.strong, kit.normal, kit.sub)
        claps = List(8) { clap(it) }
        // The metronome is heard with the sound it plays: the blip, near enough.
        val blip = Synth.render(Kit.BLIP, sampleRate)
        clicks[0] = blip.strong
        clicks[1] = blip.normal
        return true
    }

    private fun clap(seed: Int): FloatArray {
        val r = Random(seed)
        val attack = (rate * 0.0003).toInt()
        val out = FloatArray(rate * 60 / 1000) { i ->
            val t = i.toDouble() / rate
            val env = if (i < attack) i.toDouble() / attack else exp(-(t - attack.toDouble() / rate) / 0.012)
            ((r.nextDouble() * 2 - 1) * env).toFloat()
        }
        val loudest = out.maxOf { kotlin.math.abs(it) }
        return FloatArray(out.size) { out[it] / loudest }
    }

    /** The input frame on which a sound made at [nanos] reaches the microphone. */
    private fun frameOf(nanos: Long) = ((nanos + ACOUSTIC_NANOS - started) * rate / 1e9).toLong()

    override fun read(buffer: FloatArray, count: Int): Int {
        // Keep to real time, as a microphone would.
        val until = started + ((frames + count) * 1e9 / rate).toLong()
        val wait = until - System.nanoTime()
        if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())

        val playing = Metronome.isPlaying
        Metronome.placedSince(lastPlaced)?.forEach { p ->
            lastPlaced = p.serial
            val clap = if (Ear.mode == EarMode.COACH && p.tick.isBeat) CLAP_MEAN_MILLIS + gaussian() * CLAP_SPREAD_MILLIS else null
            coming += Coming(p, clap)
        }
        // A click sounds when the speaker's clock, as it reads by then, says it does: a click
        // placed early on, while that clock was still settling, sounds when it really plays.
        val soon = frames + count + rate / 50
        val each = coming.iterator()
        while (each.hasNext()) {
            val c = each.next()
            val p = c.placed
            val heard = Metronome.heardAt(p.frame) ?: p.nanos
            val first = minOf(frameOf(heard), c.clapMillis?.let { frameOf(heard + (it * 1e6).toLong()) } ?: Long.MAX_VALUE)
            if (first > soon) continue
            each.remove()
            if (p.level > 0f) due += Sound(frameOf(heard), clicks.getValue(if (p.tick.isDownbeat) 0 else 1), p.level * ECHO)
            c.clapMillis?.let { due += Sound(frameOf(heard + (it * 1e6).toLong()), claps[random.nextInt(claps.size)], 0.55f + 0.2f * random.nextFloat()) }
        }
        if (!playing && Ear.mode == EarMode.TEMPO) {
            // A drum loop: kick on one and three, snare on two and four, hi-hats in quavers.
            val beat = rate * 60.0 / LOOP_BPM
            while (loopBeat * beat < frames + count + rate) {
                val at = (loopBeat * beat).toLong()
                due += Sound(at, drums[if (loopBeat % 2 == 0L) 0 else 1], 0.6f)
                due += Sound(at, drums[2], 0.4f)
                due += Sound(at + (beat / 2).toLong(), drums[2], 0.3f)
                loopBeat++
            }
        } else {
            loopBeat = (frames / (rate * 60.0 / LOOP_BPM)).toLong() + 1
        }

        for (i in 0 until count) buffer[i] = ((random.nextFloat() * 2 - 1) * HISS)
        val end = frames + count
        val sounding = due.iterator()
        while (sounding.hasNext()) {
            val s = sounding.next()
            if (s.frame >= end) continue
            for (i in maxOf(s.frame, frames) until minOf(end, s.frame + s.samples.size)) {
                buffer[(i - frames).toInt()] += s.samples[(i - s.frame).toInt()] * s.gain
            }
            if (s.frame + s.samples.size <= end) sounding.remove()
        }
        frames = end
        return count
    }

    private fun gaussian(): Double {
        var sum = 0.0
        repeat(6) { sum += random.nextDouble() }
        return (sum - 3.0) / 0.7071
    }

    /** Says each frame was heard [STAMP_ERROR_NANOS] off the truth, as some phones do. */
    override fun stamp(): InputStamp = InputStamp(frames, started + (frames * 1e9 / rate).toLong() + STAMP_ERROR_NANOS)

    override fun close() = Unit

    companion object {
        /** How far the clapping player is behind the click on average, and how loosely they keep it. */
        const val CLAP_MEAN_MILLIS = 6.0
        const val CLAP_SPREAD_MILLIS = 7.0

        const val LOOP_BPM = 123.0

        /** The click through the air, from speaker to microphone. */
        private const val ACOUSTIC_NANOS = 400_000L
        private const val STAMP_ERROR_NANOS = -21_000_000L
        private const val ECHO = 0.25f
        private const val HISS = 0.003f
    }
}
