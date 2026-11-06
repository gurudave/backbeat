package app.djungle.backbeat.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * The click sounds. Each is synthesised in code when the metronome starts, so there are no
 * sample files to ship, and any of them can be retuned in a line or two.
 */
enum class Kit(val label: String) {
    /** A square-wave blip from an arcade cabinet. */
    BLIP("blip"),

    /** The pure beep of a digital metronome. */
    BEEP("beep"),

    /** A wood block: a short knock with a ring. */
    WOOD("wood"),

    /** A drum machine's cowbell, two clanging square waves. */
    COWBELL("cowbell"),

    /** Kick on the accents, snare on the beats, hi-hat on the in-betweens. */
    DRUMS("drums"),
}

/** One kit's three sounds, at one sample rate. */
class KitSounds(
    /** For accented beats. */
    val strong: FloatArray,
    /** For ordinary beats (and, played quietly, ghost beats). */
    val normal: FloatArray,
    /** For the clicks between beats. */
    val sub: FloatArray,
) {
    /** Starts the sound for [tick], [offset] frames into the next block [mixer] renders. */
    fun play(tick: Tick, mixer: Mixer, offset: Int) {
        if (!tick.audible) return
        if (tick.step > 0) {
            mixer.trigger(sub, 1f, offset)
            return
        }
        when (tick.accent) {
            Accent.STRONG -> mixer.trigger(strong, 1f, offset)
            Accent.NORMAL -> mixer.trigger(normal, 1f, offset)
            Accent.SOFT -> mixer.trigger(normal, GHOST_GAIN, offset)
            Accent.MUTE -> Unit
        }
    }

    companion object {
        /** How loud a ghost beat is next to an ordinary one: about 9 dB down. */
        const val GHOST_GAIN = 0.35f
    }
}

/** The volume setting (0.1 to 1) as a gain. Squared, so the bar's steps sound evenly spaced. */
fun loudness(volume: Float): Float = volume.coerceIn(0f, 1f).let { it * it }

object Synth {

    fun render(kit: Kit, sampleRate: Int): KitSounds = when (kit) {
        Kit.BLIP -> KitSounds(
            strong = blip(sampleRate, 1568.0, 0.07, 0.024, 0.9f),
            normal = blip(sampleRate, 1046.5, 0.06, 0.02, 0.72f),
            sub = blip(sampleRate, 784.0, 0.04, 0.012, 0.42f),
        )
        Kit.BEEP -> KitSounds(
            strong = beep(sampleRate, 1760.0, 0.075, 0.85f),
            normal = beep(sampleRate, 880.0, 0.065, 0.72f),
            sub = beep(sampleRate, 660.0, 0.04, 0.4f),
        )
        Kit.WOOD -> KitSounds(
            strong = wood(sampleRate, 1250.0, 0.034, 0.95f, seed = 1),
            normal = wood(sampleRate, 920.0, 0.03, 0.8f, seed = 2),
            sub = wood(sampleRate, 1450.0, 0.014, 0.42f, seed = 3),
        )
        Kit.COWBELL -> KitSounds(
            strong = cowbell(sampleRate, 1.12, 0.14, 0.9f),
            normal = cowbell(sampleRate, 1.0, 0.09, 0.74f),
            sub = cowbell(sampleRate, 1.0, 0.025, 0.36f),
        )
        Kit.DRUMS -> KitSounds(
            strong = kick(sampleRate, 0.95f, seed = 4),
            normal = snare(sampleRate, 0.82f, seed = 5),
            sub = hat(sampleRate, 0.4f, seed = 6),
        )
    }

    // ---- the instruments ----

    /** A square wave built from its odd harmonics (so it does not alias) with a falling pitch. */
    private fun blip(sr: Int, freq: Double, seconds: Double, decay: Double, peak: Float): FloatArray {
        val out = FloatArray((sr * seconds).toInt())
        var phase = 0.0
        for (i in out.indices) {
            val t = i.toDouble() / sr
            val f = freq * (1.0 - 0.06 * min(t / 0.03, 1.0))
            phase += 2 * PI * f / sr
            out[i] = (square(phase, f, sr, maxHarmonic = 9) * exp(-t / decay)).toFloat()
        }
        return finish(out, sr, peak)
    }

    /** A sine tone with a flat top: the beep of a quartz metronome. */
    private fun beep(sr: Int, freq: Double, seconds: Double, peak: Float): FloatArray {
        val out = FloatArray((sr * seconds).toInt())
        val release = 0.02
        for (i in out.indices) {
            val t = i.toDouble() / sr
            val env = min(1.0, (seconds - t) / release).coerceAtLeast(0.0)
            out[i] = (sin(2 * PI * freq * t) * env).toFloat()
        }
        return finish(out, sr, peak, attack = 0.002)
    }

    /**
     * A wood block: three ringing modes at the inharmonic ratios of a struck bar, the higher
     * ones dying away quicker, over a tiny burst of noise for the strike itself.
     */
    private fun wood(sr: Int, freq: Double, decay: Double, peak: Float, seed: Int): FloatArray {
        val out = FloatArray((sr * decay * 6).toInt())
        val modes = listOf(Triple(1.0, 1.0, decay), Triple(2.76, 0.42, decay * 0.45), Triple(5.4, 0.2, decay * 0.25))
        val random = Random(seed)
        val strike = (sr * 0.0015).toInt()
        for (i in out.indices) {
            val t = i.toDouble() / sr
            var v = 0.0
            for ((ratio, amp, tau) in modes) v += amp * sin(2 * PI * freq * ratio * t) * exp(-t / tau)
            if (i < strike) v += 0.35 * (random.nextDouble() * 2 - 1) * (1.0 - i.toDouble() / strike)
            out[i] = v.toFloat()
        }
        return finish(highPass(out, sr, 300.0), sr, peak)
    }

    /**
     * The drum-machine cowbell: two square waves a little under a fifth apart, a sharp clang
     * that settles into a short ring, filtered to the band where a cowbell lives.
     */
    private fun cowbell(sr: Int, scale: Double, ring: Double, peak: Float): FloatArray {
        val out = FloatArray((sr * (0.03 + ring * 3.2)).toInt())
        val f1 = 540.0 * scale
        val f2 = 800.0 * scale
        for (i in out.indices) {
            val t = i.toDouble() / sr
            val env = 0.62 * exp(-t / 0.012) + 0.38 * exp(-t / ring)
            val tone = square(2 * PI * f1 * t, f1, sr, maxHarmonic = 7) + square(2 * PI * f2 * t, f2, sr, maxHarmonic = 7)
            out[i] = (tone * env).toFloat()
        }
        return finish(lowPass(highPass(out, sr, 450.0), sr, 4500.0), sr, peak)
    }

    /**
     * A kick drum: a sine that drops in pitch, driven into soft clipping. The overtones that
     * adds are what a phone's little speaker can actually play.
     */
    private fun kick(sr: Int, peak: Float, seed: Int): FloatArray {
        val out = FloatArray((sr * 0.32).toInt())
        val random = Random(seed)
        var phase = 0.0
        for (i in out.indices) {
            val t = i.toDouble() / sr
            val f = 62.0 + 170.0 * exp(-t / 0.035)
            phase += 2 * PI * f / sr
            var v = tanh(2.4 * sin(phase)) * exp(-t / 0.12)
            if (i < sr * 0.002) v += 0.4 * (random.nextDouble() * 2 - 1)
            out[i] = v.toFloat()
        }
        return finish(out, sr, peak)
    }

    /** A snare: a short drum tone under a longer hiss of brightened noise for the wires. */
    private fun snare(sr: Int, peak: Float, seed: Int): FloatArray {
        val n = (sr * 0.24).toInt()
        val random = Random(seed)
        val noise = highPass(FloatArray(n) { (random.nextDouble() * 2 - 1).toFloat() }, sr, 1200.0)
        val out = FloatArray(n)
        for (i in out.indices) {
            val t = i.toDouble() / sr
            val body = sin(2 * PI * 190.0 * t) * exp(-t / 0.035)
            out[i] = (0.55 * body + 0.75 * noise[i] * exp(-t / 0.06)).toFloat()
        }
        return finish(out, sr, peak)
    }

    /** A closed hi-hat: a very short burst of high noise. */
    private fun hat(sr: Int, peak: Float, seed: Int): FloatArray {
        val n = (sr * 0.06).toInt()
        val random = Random(seed)
        val noise = highPass(highPass(FloatArray(n) { (random.nextDouble() * 2 - 1).toFloat() }, sr, 6000.0), sr, 6000.0)
        for (i in noise.indices) noise[i] *= exp(-(i.toDouble() / sr) / 0.011).toFloat()
        return finish(noise, sr, peak)
    }

    // ---- helpers ----

    /** A band-limited square wave at [phase]: odd harmonics up to [maxHarmonic], below Nyquist. */
    private fun square(phase: Double, freq: Double, sr: Int, maxHarmonic: Int): Double {
        var v = 0.0
        var k = 1
        while (k <= maxHarmonic && freq * k < sr * 0.45) {
            v += sin(phase * k) / k
            k += 2
        }
        return v
    }

    private fun highPass(x: FloatArray, sr: Int, cutoff: Double): FloatArray {
        val rc = 1.0 / (2 * PI * cutoff)
        val a = (rc / (rc + 1.0 / sr)).toFloat()
        val y = FloatArray(x.size)
        for (i in 1 until x.size) y[i] = a * (y[i - 1] + x[i] - x[i - 1])
        return y
    }

    private fun lowPass(x: FloatArray, sr: Int, cutoff: Double): FloatArray {
        val dt = 1.0 / sr
        val a = (dt / (1.0 / (2 * PI * cutoff) + dt)).toFloat()
        val y = FloatArray(x.size)
        var last = 0f
        for (i in x.indices) {
            last += a * (x[i] - last)
            y[i] = last
        }
        return y
    }

    /**
     * Scales to [peak], eases in over [attack] seconds and out over the last few milliseconds,
     * so no sound starts or stops with a click of its own.
     */
    private fun finish(x: FloatArray, sr: Int, peak: Float, attack: Double = 0.0003): FloatArray {
        val loudest = x.maxOfOrNull { abs(it) } ?: 0f
        if (loudest > 0f) {
            val scale = peak / loudest
            for (i in x.indices) x[i] *= scale
        }
        val inFrames = (sr * attack).toInt().coerceAtLeast(1)
        for (i in 0 until min(inFrames, x.size)) x[i] *= i.toFloat() / inFrames
        val outFrames = min((sr * 0.004).toInt(), x.size)
        for (i in 0 until outFrames) x[x.size - 1 - i] *= i.toFloat() / outFrames
        return x
    }
}

/**
 * Plays sounds started at any frame, adding them together. Belongs to the audio thread.
 */
class Mixer(private val maxVoices: Int = 32) {
    private class Voice(val samples: FloatArray, val gain: Float, var start: Int) {
        var position = 0
    }

    private val voices = ArrayList<Voice>(maxVoices)

    /** Starts [samples] at [offset] frames into the next block. */
    fun trigger(samples: FloatArray, gain: Float, offset: Int) {
        if (voices.size >= maxVoices) voices.removeAt(0)
        voices += Voice(samples, gain, offset)
    }

    /** Fills the first [frames] of [out] with every voice playing, times [gain]. */
    fun render(out: FloatArray, frames: Int, gain: Float) {
        java.util.Arrays.fill(out, 0, frames, 0f)
        val each = voices.iterator()
        while (each.hasNext()) {
            val voice = each.next()
            var i = voice.start
            val samples = voice.samples
            while (i < frames && voice.position < samples.size) {
                out[i] += samples[voice.position] * voice.gain
                voice.position++
                i++
            }
            voice.start = 0
            if (voice.position >= samples.size) each.remove()
        }
        for (i in 0 until frames) out[i] = (out[i] * gain).coerceIn(-1f, 1f)
    }

    fun clear() = voices.clear()
}
