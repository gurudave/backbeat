package app.djungle.backbeat.engine

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs
import kotlin.math.log10

/** What the Ear is listening for. */
enum class EarMode {
    OFF,

    /** The player, playing along with the metronome: each hit is judged. */
    COACH,

    /** Music from a speaker nearby, to find its tempo. */
    TEMPO,

    /** The metronome's own clicks, to learn how late the microphone hears them. */
    CALIBRATE,
}

/** What the Ear is up to, for the screen. */
data class EarState(
    val mode: EarMode = EarMode.OFF,
    /** Why it could not listen, if it could not. */
    val problem: String? = null,
    /** How loud the microphone hears things, 0 to 1, for a meter. */
    val level: Float = 0f,
    /** The tempo finder's latest guess, once it has one. */
    val tempo: TempoGuess? = null,
    /** True once the guess has held steady long enough to call it found. */
    val found: Boolean = false,
    val heardSeconds: Float = 0f,
    /** The last calibration that worked. */
    val calibration: Calibration? = null,
    /** Goes up by one each time a calibration fails. */
    val calibrationFailures: Int = 0,
    /** The last session of playing along that was long enough to judge. */
    val card: Scorecard? = null,
    /** Goes up by one with each new [card]. */
    val cards: Int = 0,
)

/** One frame of the input and the moment it reached the microphone, by [System.nanoTime]. */
class InputStamp(val frame: Long, val nanos: Long)

/** Where the Ear's sound comes from: the phone's microphone, or a stand-in for the CI preview. */
interface Microphone {
    /** Opens it, ready to read at [sampleRate]. False if it cannot be. */
    fun open(sampleRate: Int): Boolean

    /** Reads [count] frames into [buffer], waiting for them. Returns the frames read, or less than 1 on failure. */
    fun read(buffer: FloatArray, count: Int): Int

    /** A recent frame and when it was heard, if the microphone can say. */
    fun stamp(): InputStamp?

    fun close()
}

/**
 * The Ear: listens through the microphone while the metronome plays, to judge the player's
 * timing, or on its own, to find the tempo of music nearby.
 *
 * Its thread reads the microphone in 5 ms blocks. Every hit it hears is put on the speaker's
 * clock: the microphone says when each frame reached it, calibration says how far that is
 * from the truth, and the metronome says when each of its clicks came out of the speaker. So
 * a hit can be set against the click it was aimed at, to the millisecond, however long the
 * phone takes to record and play.
 *
 * Through the phone's own speaker, the microphone hears the click as well as the player. Each
 * click is shadowed (see [OnsetDetector.shadow]): only a hit well louder than the click, as
 * calibration measured it, counts. With headphones there is nothing to shadow.
 *
 * Nothing heard is kept: only the times of hits, and only while it listens.
 */
object Ear {
    private const val TAG = "Ear"

    private val mutableState = MutableStateFlow(EarState())
    val state: StateFlow<EarState> = mutableState

    private val mutableHits = MutableStateFlow<List<Judgment>>(emptyList())

    /** The current session's latest hits, newest last. */
    val hits: StateFlow<List<Judgment>> = mutableHits

    /** The running score of the current session. */
    private val mutableLive = MutableStateFlow(Scorecard())
    val live: StateFlow<Scorecard> = mutableLive

    /** Makes the microphone. The CI preview swaps in a stand-in that claps along. */
    @Volatile var microphone: (Context) -> Microphone = { AndroidMicrophone(it) }

    /** A correction the player can make by hand, in milliseconds, for Bluetooth and the like. */
    @Volatile var nudgeMillis: Int = 0

    @Volatile private var session = 0

    val mode: EarMode get() = state.value.mode

    /** Restores a calibration saved earlier. */
    fun remember(calibration: Calibration?) {
        mutableState.update { it.copy(calibration = calibration) }
    }

    /** Starts judging the player against the metronome, which should be playing. */
    fun coach(context: Context, bpm: Int) {
        mutableHits.value = emptyList()
        mutableLive.value = Scorecard(bpm = bpm)
        begin(context, EarMode.COACH, bpm)
    }

    /** Starts listening for the tempo of music nearby. The metronome stops, or it would hear itself. */
    fun findTempo(context: Context) {
        Metronome.stop()
        mutableState.update { it.copy(tempo = null, found = false, heardSeconds = 0f) }
        begin(context, EarMode.TEMPO, 0)
    }

    /**
     * Plays a few bars of [groove]'s sound at 120 and listens to them, to learn how late the
     * microphone hears things. Then puts the metronome back to [groove] and [practice].
     */
    fun calibrate(context: Context, groove: Groove, practice: Practice) {
        if (headphones(context)) {
            fail("unplug the headphones to calibrate: it listens to its own speaker")
            return
        }
        Metronome.stop()
        Metronome.update(Groove(bpm = 120, kit = groove.kit, volume = maxOf(groove.volume, 0.8f)), Practice())
        Metronome.start(context)
        restore = groove to practice
        begin(context, EarMode.CALIBRATE, 120)
    }

    @Volatile private var restore: Pair<Groove, Practice>? = null

    /** Stops listening. A session of playing along long enough to judge leaves its scorecard. */
    fun stop() {
        session++
        val was = mode
        if (was == EarMode.OFF) return
        if (was == EarMode.COACH) {
            val card = mutableLive.value
            if (card.hits >= Scorecard.MIN_HITS) mutableState.update { it.copy(card = card, cards = it.cards + 1) }
        }
        if (was == EarMode.CALIBRATE) finishCalibrating()
        mutableState.update { it.copy(mode = EarMode.OFF, level = 0f) }
    }

    private fun fail(problem: String) {
        mutableState.update { it.copy(mode = EarMode.OFF, problem = problem, calibrationFailures = it.calibrationFailures + 1) }
    }

    private fun finishCalibrating() {
        Metronome.stop()
        restore?.let { (groove, practice) -> Metronome.update(groove, practice) }
        restore = null
    }

    private fun begin(context: Context, mode: EarMode, bpm: Int) {
        val mine = ++session
        val app = context.applicationContext
        val echoFree = headphones(app)
        mutableState.update { it.copy(mode = mode, problem = null, level = 0f) }
        Thread({ listen(app, mine, mode, bpm, echoFree) }, "backbeat-ear").start()
    }

    /** True if the sound is going to headphones, where the microphone cannot hear it. */
    fun headphones(context: Context): Boolean {
        val manager = context.getSystemService(AudioManager::class.java) ?: return false
        val private = mutableSetOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        )
        if (Build.VERSION.SDK_INT >= 31) private += AudioDeviceInfo.TYPE_BLE_HEADSET
        return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in private }
    }

    /** The audio thread for one session of listening. */
    private fun listen(app: Context, mine: Int, mode: EarMode, bpm: Int, echoFree: Boolean) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val mic = microphone(app)
        val rate = RATES.firstOrNull { runCatching { mic.open(it) }.getOrDefault(false) }
        if (rate == null) {
            if (session == mine) {
                mutableState.update { it.copy(mode = EarMode.OFF, problem = "the microphone is busy, or missing") }
                if (mode == EarMode.CALIBRATE) finishCalibrating()
            }
            return
        }
        val block = rate / 200
        val buffer = FloatArray(block)
        val detector = OnsetDetector(rate)
        val finder = if (mode == EarMode.TEMPO) TempoFinder(rate) else null
        val judge = Judge(bpm)
        val calibration = state.value.calibration
        val offset = (calibration?.offsetNanos ?: 0L) + nudgeMillis * 1_000_000L
        val echo = calibration?.echo ?: UNCALIBRATED_ECHO

        var frames = 0L
        var reads = 0L
        var stamp: InputStamp? = null
        var lastPlaced = -1L
        val clicks = ArrayDeque<Heard>()
        val pending = ArrayList<Long>()
        val heard = ArrayList<Pair<Long, Float>>()
        val recent = ArrayList<Judgment>()
        var peak = 0f
        val started = System.nanoTime()
        var guesses = ArrayList<Int>()
        var foundAt = 0L
        // When the first click is heard. The speaker's clock is least sure of itself just after
        // START, so the first second is left out of calibration and judging.
        var firstClick = 0L

        try {
            while (session == mine) {
                val n = mic.read(buffer, block)
                if (n <= 0) throw IllegalStateException("microphone read failed: $n")
                frames += n
                reads++
                if (reads % 4 == 1L) mic.stamp()?.let { stamp = it }
                // Without the microphone's own word, the newest frame is taken as heard just now.
                val clock = stamp ?: InputStamp(frames, System.nanoTime())
                fun micNanos(frame: Long) = clock.nanos + ((frame - clock.frame) * 1e9 / rate).toLong()
                fun micFrame(nanos: Long) = clock.frame + ((nanos - clock.nanos) * rate / 1e9).toLong()

                val now = System.nanoTime()
                if (mode != EarMode.TEMPO) {
                    Metronome.placedSince(lastPlaced)?.forEach { p ->
                        lastPlaced = p.serial
                        clicks.addLast(Heard(p))
                        if (firstClick == 0L) firstClick = p.nanos
                    }
                    for (c in clicks) {
                        if (c.firm) continue
                        // Each click is timed by the speaker's clock as it reads when the click plays:
                        // a stall in the sound revises the clock, but not for clicks already heard.
                        Metronome.heardAt(c.placed.frame)?.let { c.nanos = it }
                        if (c.nanos <= now) c.firm = true
                        // Its shadow is laid just before it reaches the microphone, by that same clock.
                        if (!c.shadowed && c.nanos < now + SHADOW_AHEAD_NANOS) {
                            c.shadowed = true
                            if (mode == EarMode.COACH && !echoFree && c.placed.level > 0f) {
                                // When the microphone hears this click: the speaker's time, back on its own clock.
                                val at = micFrame(c.nanos - offset)
                                detector.shadow(at - rate * 10L / 1000, at + rate * 30L / 1000, echo * c.placed.level * SHADOW_MARGIN)
                            }
                        }
                    }
                }
                if (mode == EarMode.COACH) while (clicks.isNotEmpty() && clicks.first().nanos < now - KEEP_NANOS) clicks.removeFirst()

                detector.process(buffer, n) { onset ->
                    val at = micNanos(onset.frame)
                    when (mode) {
                        EarMode.COACH -> pending += at + offset
                        EarMode.CALIBRATE -> heard += at to onset.peak
                        else -> Unit
                    }
                }
                finder?.feed(buffer, n)
                for (i in 0 until n) peak = maxOf(peak, abs(buffer[i]))

                // Judge each hit once the click after it is known, or it is long past.
                if (pending.isNotEmpty()) {
                    val iterator = pending.iterator()
                    while (iterator.hasNext()) {
                        val hit = iterator.next()
                        val after = clicks.firstOrNull { it.nanos > hit }
                        if (after == null && hit > now - KEEP_NANOS / 2) continue
                        iterator.remove()
                        if (firstClick == 0L || hit < firstClick + SETTLE_NANOS) continue
                        val before = clicks.lastOrNull { it.nanos <= hit }
                        fun click(c: Heard) = Click(c.nanos, c.placed.tick)
                        val judgment = judge.judge(hit, before?.let(::click), after?.let(::click)) ?: continue
                        recent += judgment
                        if (recent.size > RECENT) recent.removeAt(0)
                        if (session == mine) {
                            mutableHits.value = recent.toList()
                            mutableLive.value = judge.card
                        }
                    }
                }

                // The meter, and the tempo finder's progress, a few times a second.
                if (reads % 10 == 0L && session == mine) {
                    val level = if (peak <= 0f) 0f else ((20 * log10(peak) + 60f) / 60f).coerceIn(0f, 1f)
                    peak = 0f
                    mutableState.update { it.copy(level = level) }
                }
                if (finder != null && reads % 100 == 0L && session == mine) {
                    val guess = finder.estimate()
                    if (guess != null && guess.confidence >= SURE) guesses += guess.rounded
                    if (guesses.size > 3) guesses = ArrayList(guesses.takeLast(3))
                    val steady = guesses.size == 3 && guesses.max() - guesses.min() <= 1 && finder.heardSeconds >= FOUND_AFTER_SECONDS
                    mutableState.update {
                        it.copy(tempo = guess ?: it.tempo, found = it.found || steady, heardSeconds = finder.heardSeconds.toFloat())
                    }
                    if (steady && foundAt == 0L) foundAt = now
                    // Found, it listens a moment longer to firm up the answer, then lets the microphone go.
                    if (foundAt != 0L && now - foundAt > LINGER_NANOS) break
                    if (now - started > TEMPO_GIVE_UP_NANOS) break
                }

                if (mode == EarMode.CALIBRATE) {
                    // Done once enough clicks have been played and had time to be heard.
                    val played = clicks.filter {
                        it.placed.level > 0f && it.firm && it.nanos < now - LATE_NANOS && it.nanos >= firstClick + SETTLE_NANOS
                    }
                    val gaveUp = now - started > CALIBRATE_GIVE_UP_NANOS
                    if (played.size >= CALIBRATION_CLICKS || gaveUp) {
                        val times = played.map { it.nanos to it.placed.level }
                        val result = Calibration.measure(times, heard)
                        Log.i(TAG, "calibration: $result from ${played.size} clicks and ${heard.size} hits")
                        if (result == null) {
                            // How late each click was heard, for anyone looking into a phone it fails on.
                            val lateness = times.map { (at, _) -> heard.minByOrNull { abs(it.first - at) }?.let { (it.first - at) / 1e6 } }
                            Log.i(TAG, "calibration offsets (ms): ${lateness.joinToString { it?.let { "%.1f".format(it) } ?: "-" }}")
                        }
                        if (session == mine) {
                            session++
                            finishCalibrating()
                            if (result != null) {
                                mutableState.update { it.copy(mode = EarMode.OFF, calibration = result, level = 0f) }
                            } else {
                                mutableState.update {
                                    it.copy(
                                        mode = EarMode.OFF,
                                        level = 0f,
                                        problem = "it could not hear itself: turn the volume up, and try somewhere quieter",
                                        calibrationFailures = it.calibrationFailures + 1,
                                    )
                                }
                            }
                        }
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ear stopped", e)
            if (session == mine) {
                mutableState.update { it.copy(mode = EarMode.OFF, problem = "the microphone stopped") }
                if (mode == EarMode.CALIBRATE) finishCalibrating()
            }
        } finally {
            mic.close()
        }
        if (mode == EarMode.TEMPO && session == mine) mutableState.update { it.copy(mode = EarMode.OFF, level = 0f) }
    }

    /** A click as the Ear keeps it: when it is heard, firm once it has played, and whether it is shadowed yet. */
    private class Heard(val placed: Placed) {
        var nanos = placed.nanos
        var firm = false
        var shadowed = false
    }

    /** A click's shadow is laid this long before it is heard. */
    private const val SHADOW_AHEAD_NANOS = 60_000_000L

    /** Sample rates to try, best first. */
    private val RATES = listOf(48_000, 44_100)

    /** Hits are judged against clicks kept for this long. */
    private const val KEEP_NANOS = 3_000_000_000L

    /** How many of the session's latest hits are handed to the screen. */
    private const val RECENT = 24

    /** A hit in a click's shadow must be this many times as loud as the click is to the microphone. */
    private const val SHADOW_MARGIN = 2.5f

    /** Before calibration, a guess at how loud the click is to the microphone. */
    private const val UNCALIBRATED_ECHO = 0.5f

    /** Calibration listens to this many clicks... */
    private const val CALIBRATION_CLICKS = 10

    /** ...each given this long to arrive... */
    private const val LATE_NANOS = 400_000_000L

    /** ...and gives up after this long. */
    private const val CALIBRATE_GIVE_UP_NANOS = 11_000_000_000L

    /** How long after the first click the speaker's clock is trusted for calibration and judging. */
    private const val SETTLE_NANOS = 1_000_000_000L

    /** A tempo guess is only counted towards "found" if the finder is this sure of it. */
    private const val SURE = 0.35f

    private const val FOUND_AFTER_SECONDS = 6.0
    private const val LINGER_NANOS = 2_500_000_000L
    private const val TEMPO_GIVE_UP_NANOS = 40_000_000_000L
}

/**
 * The phone's microphone, with as little done to the sound as the phone allows: no automatic
 * gain or noise suppression, which would blunt the very hits it listens for.
 */
class AndroidMicrophone(context: Context) : Microphone {
    private var record: AudioRecord? = null
    private val timestamp = AudioTimestamp()
    private val audio = context.getSystemService(AudioManager::class.java)

    /** The raw source, where the phone offers it; otherwise the one meant for speech recognition, nearly as raw. */
    private val source = if (audio?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") {
        MediaRecorder.AudioSource.UNPROCESSED
    } else {
        MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    @SuppressLint("MissingPermission")
    override fun open(sampleRate: Int): Boolean {
        close()
        val minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        if (minimum <= 0) return false
        val made = runCatching {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minimum, sampleRate / 10 * Float.SIZE_BYTES))
                .build()
        }.getOrNull() ?: return false
        if (made.state != AudioRecord.STATE_INITIALIZED) {
            made.release()
            return false
        }
        // With a headset plugged in, still listen through the phone's own microphone.
        audio?.getDevices(AudioManager.GET_DEVICES_INPUTS)
            ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?.let { made.preferredDevice = it }
        runCatching {
            made.startRecording()
        }.onFailure {
            made.release()
            return false
        }
        if (made.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            made.release()
            return false
        }
        record = made
        return true
    }

    override fun read(buffer: FloatArray, count: Int): Int =
        record?.read(buffer, 0, count, AudioRecord.READ_BLOCKING) ?: -1

    override fun stamp(): InputStamp? {
        val r = record ?: return null
        return if (r.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
            InputStamp(timestamp.framePosition, timestamp.nanoTime)
        } else {
            null
        }
    }

    override fun close() {
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
    }
}
