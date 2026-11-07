package app.djungle.backbeat.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A tempo heard in some music, and how sure of it the finder is (0 to 1). */
data class TempoGuess(val bpm: Double, val confidence: Float) {
    val rounded: Int get() = bpm.roundToInt().coerceIn(MIN_BPM, MAX_BPM)

    /** Half and double the tempo, where the metronome can play them: music is often counted either way. */
    val alternatives: List<Int>
        get() = listOf(bpm / 2, bpm * 2).map { it.roundToInt() }.filter { it in MIN_BPM..MAX_BPM }
}

/**
 * Works out the tempo of music it is played, from a phone's microphone held near a speaker.
 *
 * Every 5 ms it takes the spectrum of the last 20 ms and adds up how much louder each band has
 * just become (the "spectral flux"), which peaks wherever a note or a drum starts. The tempo
 * is the spacing at which that pattern of peaks best repeats: it compares the pattern with
 * itself shifted by every spacing from 40 to 240 BPM (an autocorrelation), favouring spacings
 * that also repeat at two and four beats, as a bar of music does, and gently favouring the
 * tempos music is most often in, so a song at 120 is not called 60 or 240. It then measures
 * the spacing over four beats rather than one, to a fraction of a BPM.
 */
class TempoFinder(sampleRate: Int, seconds: Double = 12.0) {
    private val size = Integer.highestOneBit((sampleRate * 0.02).toInt().coerceAtLeast(64)) * 2
    private val hop = (sampleRate / FRAMES_PER_SECOND).coerceAtLeast(1)

    /** Flux values a second. */
    val frameRate: Double = sampleRate.toDouble() / hop

    private val window = FloatArray(size) { (0.5 - 0.5 * cos(2 * PI * it / size)).toFloat() }
    private val input = FloatArray(size)
    private var inputAt = 0
    private var sinceHop = 0

    private val re = DoubleArray(size)
    private val im = DoubleArray(size)
    private val bins = (size * 8000.0 / sampleRate).toInt().coerceAtMost(size / 2)
    private val last = FloatArray(bins)
    private val fft = Fft(size)

    private val flux = FloatArray((seconds * frameRate).toInt())
    private var fluxCount = 0L

    /** Seconds of music heard so far (up to the length it keeps). */
    val heardSeconds: Double get() = minOf(fluxCount, flux.size.toLong()) / frameRate

    fun clear() {
        input.fill(0f)
        last.fill(0f)
        fluxCount = 0
        sinceHop = 0
    }

    fun feed(samples: FloatArray, count: Int) {
        for (i in 0 until count) {
            input[inputAt] = samples[i]
            inputAt = (inputAt + 1) % size
            if (++sinceHop >= hop) {
                sinceHop = 0
                flux[(fluxCount % flux.size).toInt()] = spectralFlux()
                fluxCount++
            }
        }
    }

    private fun spectralFlux(): Float {
        for (i in 0 until size) {
            re[i] = (input[(inputAt + i) % size] * window[i]).toDouble()
            im[i] = 0.0
        }
        fft.transform(re, im)
        var sum = 0f
        val scale = 2.0 / size
        for (k in 1 until bins) {
            val magnitude = ln(1.0 + COMPRESSION * sqrt(re[k] * re[k] + im[k] * im[k]) * scale).toFloat()
            val rise = magnitude - last[k]
            if (rise > 0f) sum += rise
            last[k] = magnitude
        }
        return sum
    }

    /** The best guess so far, or null with too little heard to say. */
    fun estimate(): TempoGuess? {
        val n = minOf(fluxCount, flux.size.toLong()).toInt()
        if (n < frameRate * MIN_SECONDS) return null
        // Oldest first, less its average.
        val o = DoubleArray(n) { flux[((fluxCount - n + it) % flux.size).toInt()].toDouble() }
        val mean = o.average()
        for (i in o.indices) o[i] -= mean
        val shortest = (frameRate * 60 / FASTEST).toInt()
        val longest = (frameRate * 60 / SLOWEST).toInt().coerceAtMost(n / 3)
        val reach = minOf(n - 1, (n * 0.75).toInt(), longest * 4 + 4)
        val r = DoubleArray(reach + 1) { lag ->
            var s = 0.0
            for (i in 0 until n - lag) s += o[i] * o[i + lag]
            s / (n - lag)
        }
        if (r[0] <= 0.0) return null
        fun at(lag: Int) = if (lag <= reach) r[lag] else 0.0
        val score = DoubleArray(longest + 2) { lag ->
            if (lag < shortest) 0.0 else at(lag) + 0.5 * at(2 * lag) + 0.25 * at(4 * lag)
        }
        var best = -1
        var bestWeighted = 0.0
        for (lag in shortest..longest) {
            val bpm = 60 * frameRate / lag
            val octaves = ln(bpm / LIKELIEST) / ln(2.0)
            val weighted = score[lag] * exp(-0.5 * (octaves / PRIOR_OCTAVES) * (octaves / PRIOR_OCTAVES))
            if (weighted > bestWeighted) {
                bestWeighted = weighted
                best = lag
            }
        }
        if (best < 0) return null
        var period = best + vertex(score[best - 1], score[best], score[best + 1])
        // Measure across as many beats as were heard, for a finer answer.
        for (beats in intArrayOf(4, 3, 2)) {
            val centre = (period * beats).roundToInt()
            if (centre + 3 >= reach) continue
            var peak = centre
            for (lag in centre - 3..centre + 3) if (r[lag] > r[peak]) peak = lag
            if (peak <= centre - 3 || peak >= centre + 3) continue
            period = (peak + vertex(r[peak - 1], r[peak], r[peak + 1])) / beats
            break
        }
        val confidence = (score[best] / (1.75 * r[0]) * 2.5).coerceIn(0.0, 1.0).toFloat()
        return TempoGuess(60 * frameRate / period, confidence)
    }

    /** Where the top of a parabola through three evenly spaced points lies, from -0.5 to 0.5. */
    private fun vertex(a: Double, b: Double, c: Double): Double {
        val d = a - 2 * b + c
        return if (d < 0.0) (0.5 * (a - c) / d).coerceIn(-0.5, 0.5) else 0.0
    }

    private companion object {
        const val FRAMES_PER_SECOND = 200
        const val COMPRESSION = 1000.0
        const val MIN_SECONDS = 4.0
        const val FASTEST = 240.0
        const val SLOWEST = 40.0

        /** Music is most often near this tempo... */
        const val LIKELIEST = 115.0

        /** ...and rarely more than this many octaves from it. */
        const val PRIOR_OCTAVES = 1.0
    }
}

/** A plain radix-2 fast Fourier transform, in place, for one size. */
class Fft(private val n: Int) {
    private val cosTable = DoubleArray(n / 2) { cos(2 * PI * it / n) }
    private val sinTable = DoubleArray(n / 2) { -sin(2 * PI * it / n) }
    private val bits = Integer.numberOfTrailingZeros(n)

    init {
        require(n >= 2 && n and (n - 1) == 0) { "size must be a power of two" }
    }

    fun transform(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until n) {
            val j = Integer.reverse(i) ushr (32 - bits)
            if (j > i) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }
        var length = 2
        while (length <= n) {
            val half = length / 2
            val step = n / length
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosTable[k]
                    val wi = sinTable[k]
                    val xr = re[j + half] * wr - im[j + half] * wi
                    val xi = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - xr
                    im[j + half] = im[j] - xi
                    re[j] += xr
                    im[j] += xi
                    k += step
                }
                start += length
            }
            length *= 2
        }
    }
}
