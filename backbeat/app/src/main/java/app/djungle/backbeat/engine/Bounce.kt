package app.djungle.backbeat.engine

/**
 * Renders the metronome to a buffer instead of a speaker, in the same blocks and through the
 * same sequencer and mixer as the live engine. The tests and the CI preview use it to check
 * the clicks land where they should.
 */
object Bounce {

    /** The rendered audio, and the frame each click was scheduled on. */
    class Take(val audio: FloatArray, val ticks: List<Pair<Int, Tick>>)

    fun render(
        groove: Groove,
        sampleRate: Int,
        frames: Int,
        practice: Practice = Practice(),
        block: Int = 240,
    ): Take {
        val sequencer = Sequencer(sampleRate, groove, practice)
        val sounds = Synth.render(groove.kit, sampleRate)
        val mixer = Mixer()
        val out = FloatArray(frames)
        val buffer = FloatArray(block)
        val ticks = ArrayList<Pair<Int, Tick>>()
        var at = 0
        while (at < frames) {
            val n = minOf(block, frames - at)
            val start = at
            sequencer.advance(n) { offset, tick ->
                ticks += (start + offset) to tick
                sounds.play(tick, mixer, offset)
            }
            mixer.render(buffer, n, loudness(groove.volume))
            System.arraycopy(buffer, 0, out, at, n)
            at += n
        }
        return Take(out, ticks)
    }
}
