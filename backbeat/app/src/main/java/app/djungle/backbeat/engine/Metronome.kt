package app.djungle.backbeat.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** What the engine is up to, for the screen and the notification. */
data class EngineState(
    val playing: Boolean = false,
    /** The tempo being played, which a ramp may have moved away from the groove's. */
    val bpm: Int? = null,
    /** Goes up by one each time the practice timer runs out. */
    val timeUps: Int = 0,
)

/** What the listener is hearing at this moment, worked out for one frame of the display. */
class Now(
    /** The click heard most recently (a beat or a step between beats, not the cross-rhythm). */
    val tick: Tick,
    /** The beat heard most recently. */
    val beat: Tick,
    /** How far the music has come from [beat] towards the next beat, 0 to 1. */
    val beatProgress: Float,
    val sinceBeatMillis: Float,
    /** Time played since START, for the tape counter. */
    val elapsedMillis: Long,
    /** Counts every click heard; it changes exactly when a new beat or step is heard. */
    val serial: Long,
    /** The cross-rhythm's click heard most recently, if one is playing. */
    val cross: Tick? = null,
    val sinceCrossMillis: Float = 0f,
)

/** A click as placed for the speaker, for the Ear: when it will be heard, and how loud. */
class Placed(
    /** Counts every click placed since START. */
    val serial: Long,
    /** When it comes out of the speaker, by [System.nanoTime]. */
    val nanos: Long,
    val tick: Tick,
    /** Its peak level, with the volume: 0 for a click that makes no sound. */
    val level: Float,
)

/**
 * The live metronome: one audio thread feeding the sequencer's clicks to the speaker.
 *
 * The thread writes small blocks of audio, a tenth of a second ahead of the speaker, so a
 * busy phone cannot make a click late. It notes the frame each click was placed on and, from
 * the sound card's timestamps, which frame is coming out of the speaker now. The screen asks
 * [now] each frame, so the lights flash when a click is *heard*, not when it was written.
 *
 * It is a process-wide object, so it keeps playing while the screen turns round, and in the
 * background when the user has asked it to (see `TickService`).
 */
object Metronome {
    private const val TAG = "Metronome"

    private val mutableState = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = mutableState

    @Volatile private var groove = Groove()
    @Volatile private var practice = Practice()

    /** Each START gets a new session; a thread whose session has passed winds itself up. */
    @Volatile private var session = 0
    @Volatile private var running = false

    private var noisyReceiver: BroadcastReceiver? = null

    // The clicks placed so far, in a ring: the frame each one was placed on, and the click.
    private const val LOG_SIZE = 64
    private val logFrames = LongArray(LOG_SIZE)
    private val logTicks = arrayOfNulls<Tick>(LOG_SIZE)
    private val logLevels = FloatArray(LOG_SIZE)
    @Volatile private var logCount = 0L

    /** The speaker's clock: [frame] was coming out of it at [nanos]. */
    private class Anchor(val frame: Long, val nanos: Long, val sampleRate: Int)

    @Volatile private var anchor: Anchor? = null

    /** The steadied clock the display reads; it belongs to the main thread. */
    private var display: DisplayClock? = null
    private var displaySession = -1

    /** Every kit's sounds, made once for the speaker's sample rate. */
    @Volatile private var kitCache: Pair<Int, Map<Kit, KitSounds>>? = null

    val isPlaying: Boolean get() = running

    /** Makes the sounds ahead of time, off the main thread, so START has nothing to wait for. */
    fun prepare() {
        Thread({ runCatching { kits(sampleRate()) } }, "backbeat-prepare").start()
    }

    private fun kits(sampleRate: Int): Map<Kit, KitSounds> {
        kitCache?.let { (rate, kits) -> if (rate == sampleRate) return kits }
        return Kit.entries.associateWith { Synth.render(it, sampleRate) }.also { kitCache = sampleRate to it }
    }

    /** Hands over new settings. The audio thread picks them up within a few milliseconds. */
    fun update(groove: Groove, practice: Practice) {
        this.groove = groove
        this.practice = practice
    }

    fun start(context: Context) {
        if (running) return
        val app = context.applicationContext
        val mine = ++session
        running = true
        logCount = 0
        anchor = null
        mutableState.update { it.copy(playing = true, bpm = groove.bpm) }
        listenForUnplugging(app)
        Thread({ play(mine) }, "backbeat-audio").start()
    }

    fun stop() {
        if (!running) return
        running = false
        session++
        anchor = null
        mutableState.update { it.copy(playing = false, bpm = null) }
        noisyReceiver?.let { receiver ->
            noisyReceiver = null
            // The context it was registered with is the application's, which outlives this.
            runCatching { appContext?.unregisterReceiver(receiver) }
        }
    }

    private var appContext: Context? = null

    /** Headphones pulled out: stop, rather than carry on clicking out of the speaker. */
    private fun listenForUnplugging(app: Context) {
        appContext = app
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) stop()
            }
        }
        noisyReceiver = receiver
        ContextCompat.registerReceiver(
            app,
            receiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun sampleRate(): Int =
        AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC).takeIf { it in 8_000..192_000 } ?: 48_000

    private fun attributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun format(sampleRate: Int): AudioFormat = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
        .setSampleRate(sampleRate)
        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
        .build()

    /** The audio thread. */
    private fun play(mine: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val sampleRate = sampleRate()
        val allKits = kits(sampleRate)
        // Five-millisecond blocks, with about a tenth of a second queued ahead of the speaker.
        val block = sampleRate / 200
        val minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes())
                .setAudioFormat(format(sampleRate))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minimum, sampleRate / 10 * Float.SIZE_BYTES))
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "no audio track", e)
            if (session == mine) stop()
            return
        }

        var appliedGroove = groove
        var appliedPractice = practice
        val sequencer = Sequencer(sampleRate, appliedGroove, appliedPractice, seed = System.nanoTime())
        val mixer = Mixer()
        val buffer = FloatArray(block)
        val stamp = AudioTimestamp()
        var written = 0L
        var lastBpm = sequencer.bpm
        var timeUp = false
        val clock = ClockReader(sampleRate)
        try {
            track.play()
            while (running && session == mine) {
                val g = groove
                val p = practice
                if (g !== appliedGroove) {
                    sequencer.groove = g
                    appliedGroove = g
                }
                if (p !== appliedPractice) {
                    sequencer.practice = p
                    appliedPractice = p
                }
                val sounds = allKits.getValue(g.kit)
                val limit = p.stopAfterMinutes * 60L * sampleRate
                val start = written
                val gain = loudness(g.volume)
                sequencer.advance(block) { offset, tick ->
                    // Time's up: stop on the next downbeat, so the practice ends with a full bar.
                    if (limit > 0 && start + offset >= limit && tick.isDownbeat) timeUp = true
                    if (!timeUp) {
                        log(start + offset, tick, sounds.peak(tick) * gain)
                        sounds.play(tick, mixer, offset)
                    }
                }
                mixer.render(buffer, block, gain)
                var done = 0
                while (done < block && running && session == mine) {
                    val n = track.write(buffer, done, block - done, AudioTrack.WRITE_BLOCKING)
                    if (n < 0) throw IllegalStateException("audio write failed: $n")
                    done += n
                }
                written += block
                // Read the speaker's clock every few blocks; it moves smoothly in between.
                if ((written / block) % 4 == 0L) clock.read(track, stamp)?.let { if (session == mine) anchor = it }
                if (sequencer.bpm != lastBpm) {
                    lastBpm = sequencer.bpm
                    if (session == mine) mutableState.update { it.copy(bpm = lastBpm) }
                }
                if (timeUp) break
            }
            if (timeUp) {
                // Let the last bar ring out of the queue before letting go of the speaker.
                track.stop()
                Thread.sleep(250)
            } else {
                track.pause()
                track.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "audio thread stopped", e)
        } finally {
            // An underrun means the speaker ran dry and the clicks behind it came late.
            Log.i(TAG, "played ${written * 1000 / sampleRate} ms at $sampleRate Hz, ${runCatching { track.underrunCount }.getOrDefault(-1)} underruns")
            track.release()
            sessionEnded?.invoke(written * 1000 / sampleRate)
        }
        if (session == mine) {
            stop()
            if (timeUp) mutableState.update { it.copy(timeUps = it.timeUps + 1) }
        }
    }

    /** Told how long each run of the metronome played for, in milliseconds, as it ends. */
    @Volatile var sessionEnded: ((Long) -> Unit)? = null

    private fun log(frame: Long, tick: Tick, level: Float) {
        val n = logCount
        val i = (n % LOG_SIZE).toInt()
        logFrames[i] = frame
        logTicks[i] = tick
        logLevels[i] = level
        // Published last, so a reader never sees a slot before it is filled.
        logCount = n + 1
    }

    /**
     * Reads the speaker's clock for one START. Only the sound card's own timestamps are trusted:
     * the play head runs ahead of the speaker by its delay, and starting from it made the ball
     * set off early and then jump back. A phone that never gives timestamps gets the play head
     * after a short wait, as the best there is.
     */
    private class ClockReader(private val sampleRate: Int) {
        private val started = System.nanoTime()
        private var trusted = false

        fun read(track: AudioTrack, stamp: AudioTimestamp): Anchor? {
            if (track.getTimestamp(stamp) && stamp.framePosition > 0) {
                trusted = true
                return Anchor(stamp.framePosition, stamp.nanoTime, sampleRate)
            }
            if (trusted || System.nanoTime() - started < 400_000_000L) return null
            return Anchor(track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL, System.nanoTime(), sampleRate)
        }
    }

    /**
     * What is being heard at [nanoTime] (from [System.nanoTime]), or null before the first click.
     * For the display, from the main thread only: it reads a steadied clock, which [steady]
     * set to false skips (to compare the two).
     */
    fun now(nanoTime: Long = System.nanoTime(), steady: Boolean = true): Now? {
        if (!running) return null
        val clock = anchor ?: return null
        val raw = clock.frame + (nanoTime - clock.nanos) * clock.sampleRate / 1e9
        val heard = if (steady) {
            val mine = session
            val smooth = display?.takeIf { displaySession == mine } ?: DisplayClock(clock.sampleRate).also {
                display = it
                displaySession = mine
            }
            smooth.show(raw, nanoTime).toLong()
        } else {
            raw.toLong()
        }
        val count = logCount
        var tick: Tick? = null
        var serial = 0L
        var beat: Tick? = null
        var beatFrame = 0L
        var cross: Tick? = null
        var crossFrame = 0L
        var downbeatSeen = false
        var n = count - 1
        while (n >= 0 && n >= count - LOG_SIZE) {
            val i = (n % LOG_SIZE).toInt()
            val frame = logFrames[i]
            val t = logTicks[i]
            if (t != null && frame <= heard) {
                if (t.isCross) {
                    if (cross == null) {
                        cross = t
                        crossFrame = frame
                    }
                } else {
                    if (tick == null) {
                        tick = t
                        serial = n
                    }
                    if (t.isBeat && beat == null) {
                        beat = t
                        beatFrame = frame
                    }
                    if (t.isDownbeat) downbeatSeen = true
                }
                // A cross-rhythm starts on every downbeat, so looking back to one finds its click.
                if (beat != null && (cross != null || downbeatSeen)) break
            }
            n--
        }
        if (tick == null || beat == null) return null
        val since = heard - beatFrame
        val beatFrames = clock.sampleRate * 60.0 / beat.bpm
        return Now(
            tick = tick,
            beat = beat,
            beatProgress = (since / beatFrames).toFloat().coerceIn(0f, 1f),
            sinceBeatMillis = since * 1000f / clock.sampleRate,
            elapsedMillis = heard.coerceAtLeast(0) * 1000 / clock.sampleRate,
            serial = serial,
            cross = cross,
            sinceCrossMillis = (heard - crossFrame) * 1000f / clock.sampleRate,
        )
    }

    /**
     * Every click placed after [serial] that is still in the log, oldest first, with when it
     * will be heard. Null until the speaker's clock is known. From any thread.
     */
    fun placedSince(serial: Long): List<Placed>? {
        if (!running) return null
        val clock = anchor ?: return null
        val count = logCount
        val out = ArrayList<Placed>()
        var n = maxOf(serial + 1, count - LOG_SIZE + 1, 0L)
        while (n < count) {
            val i = (n % LOG_SIZE).toInt()
            val t = logTicks[i]
            if (t != null) {
                val nanos = clock.nanos + ((logFrames[i] - clock.frame) * 1e9 / clock.sampleRate).toLong()
                out += Placed(n, nanos, t, logLevels[i])
            }
            n++
        }
        return out
    }

    /** Plays one bar's worth of a kit (accent, beat, beat) so its sound can be chosen by ear. */
    fun audition(kit: Kit, volume: Float) {
        if (running) return
        Thread({
            val sampleRate = sampleRate()
            val sounds = kits(sampleRate).getValue(kit)
            val gap = (sampleRate * 0.24).toInt()
            val pcm = FloatArray(gap * 2 + maxOf(sounds.normal.size, sounds.strong.size))
            val gain = loudness(volume)
            listOf(sounds.strong, sounds.normal, sounds.normal).forEachIndexed { n, sound ->
                sound.forEachIndexed { i, v -> pcm[n * gap + i] += v * gain }
            }
            runCatching {
                val track = AudioTrack.Builder()
                    .setAudioAttributes(attributes())
                    .setAudioFormat(format(sampleRate))
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size * Float.SIZE_BYTES)
                    .build()
                try {
                    track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                    track.play()
                    Thread.sleep(pcm.size * 1000L / sampleRate + 150)
                } finally {
                    track.release()
                }
            }.onFailure { Log.w(TAG, "audition failed", it) }
        }, "backbeat-audition").start()
    }
}
