package app.djungle.backbeat

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.content.res.ResourcesCompat
import app.djungle.backbeat.data.AppSettings
import app.djungle.backbeat.engine.Accent
import app.djungle.backbeat.engine.Bounce
import app.djungle.backbeat.engine.Click
import app.djungle.backbeat.engine.Ear
import app.djungle.backbeat.engine.Groove
import app.djungle.backbeat.engine.Judge
import app.djungle.backbeat.engine.Kit
import app.djungle.backbeat.engine.Meter
import app.djungle.backbeat.engine.Metronome
import app.djungle.backbeat.engine.Now
import app.djungle.backbeat.engine.Onset
import app.djungle.backbeat.engine.OnsetDetector
import app.djungle.backbeat.engine.Practice
import app.djungle.backbeat.engine.Subdivision
import app.djungle.backbeat.engine.Synth
import app.djungle.backbeat.engine.TempoFinder
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Renders pictures and sounds of the engine for CI to look over (mode=gallery, the default):
 *   kits.png        every sound of every kit, as an oscilloscope trace
 *   bars.png        a few bars of several grooves, with a line where each click was scheduled
 *   timing.txt      for every click in those bars, how long after its line the sound starts
 *   sounds/KIT.wav  two bars of each kit, to listen to
 *
 *   ear.txt         a synthetic player clapping along over the click's echo: what the Ear made of it
 *   ear.png         that performance, with the clicks, the claps and what the Ear heard
 *   tempo.txt       the tempo finder on drum loops of known tempo
 *
 * And checks the ball's clock against the emulator's real sound card (mode=clock):
 *   clock.txt       how smoothly the ball would move for the first seconds after START
 *
 * Or opens the app with a stand-in microphone that claps along (mode=ear): see [FakeMicrophone].
 */
class DemoActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra("mode") ?: "gallery"
        // Each mode runs once per process, even if the emulator recreates the activity meanwhile.
        if (!started.add(mode)) {
            finish()
            return
        }
        val app = applicationContext
        if (mode == "ear") {
            Ear.microphone = { FakeMicrophone() }
            // A plain 4/4 to play along with, and, on this throwaway emulator only, a few
            // weeks of made-up practice so the logbook has something to show.
            AppSettings.setGroove(app, Groove(bpm = 100, kit = Kit.WOOD))
            val today = LocalDate.now()
            val random = Random(11)
            AppSettings.setLogbook(
                app,
                (1..80L).filter { random.nextInt(10) < 7 }.associate { back ->
                    today.minusDays(back).toString() to (random.nextInt(4, 55) * 60)
                },
            )
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        Thread({
            runCatching { if (mode == "clock") checkClock(app) else renderGallery(app) }
                .onFailure { Log.e(TAG, "$mode failed", it) }
            runOnUiThread { finish() }
        }, "backbeat-$mode").start()
    }

    private companion object {
        const val TAG = "BackbeatDemo"
        val started: MutableSet<String> = ConcurrentHashMap.newKeySet()
        const val RATE = 48_000
        const val INK = 0xFFE6EBFF.toInt()
        const val TUBE = 0xFF05060F.toInt()
        const val TRACE = 0xFFFFD23F.toInt()
        const val BEAT_LINE = 0xFFFF4D4D.toInt()
        const val SUB_LINE = 0xFF3FE0FF.toInt()
        const val CROSS_LINE = 0xFF7CFF4F.toInt()

        fun dir(context: Context, name: String = ""): File =
            File(context.getExternalFilesDir(null), "preview/$name").apply { mkdirs() }

        fun font(context: Context): Typeface =
            ResourcesCompat.getFont(context, R.font.vt323) ?: Typeface.MONOSPACE

        fun savePng(context: Context, name: String, bitmap: Bitmap) {
            File(dir(context), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }

        fun saveWav(file: File, audio: FloatArray) {
            DataOutputStream(FileOutputStream(file).buffered()).use { out ->
                fun int(v: Int) = out.writeInt(Integer.reverseBytes(v))
                fun short(v: Int) = out.writeShort(java.lang.Short.reverseBytes(v.toShort()).toInt())
                out.writeBytes("RIFF")
                int(36 + audio.size * 2)
                out.writeBytes("WAVEfmt ")
                int(16)
                short(1)
                short(1)
                int(RATE)
                int(RATE * 2)
                short(2)
                short(16)
                out.writeBytes("data")
                int(audio.size * 2)
                audio.forEach { short((it.coerceIn(-1f, 1f) * 32767).roundToInt()) }
            }
        }

        /** Where the ball is, in beats from START: whole beats heard, and the way to the next. */
        fun position(now: Now, beats: Int): Double = now.beat.bar * beats + now.beat.beat + now.beatProgress.toDouble()

        /**
         * Plays 120 BPM through the real sound card and, at 60 frames a second, asks where the ball
         * would be, by the steadied clock the screen uses and by the speaker's raw clock. A ball
         * that moves smoothly advances by the same amount every frame; a stutter shows as a step
         * backwards, or a step far from the rest.
         */
        fun checkClock(context: Context) {
            val bpm = 120
            Metronome.update(Groove(bpm = bpm), Practice())
            val frame = 16_666_667L
            val begin = System.nanoTime()
            Metronome.start(context)
            val steady = ArrayList<Pair<Long, Double?>>()
            val raw = ArrayList<Pair<Long, Double?>>()
            var next = begin
            while (next - begin < 3_500_000_000L) {
                val wait = next - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                steady += (next - begin) to Metronome.now(next)?.let { position(it, 4) }
                raw += (next - begin) to Metronome.now(next, steady = false)?.let { position(it, 4) }
                next += frame
            }
            Metronome.stop()
            val expected = bpm / 60.0 * frame / 1e9
            fun describe(name: String, samples: List<Pair<Long, Double?>>): String {
                val first = samples.firstOrNull { it.second != null }?.first
                val shown = samples.mapNotNull { (t, p) -> p?.let { t to it } }
                val steps = shown.zipWithNext { a, b -> b.second - a.second }
                val back = steps.filter { it < -1e-9 }
                val worst = steps.maxOfOrNull { kotlin.math.abs(it - expected) } ?: 0.0
                val judders = steps.count { kotlin.math.abs(it - expected) > expected * 0.3 }
                // How far, in milliseconds of music, the worst step was from an even one.
                val worstMs = worst / (bpm / 60.0) * 1000
                return "$name: first click shown ${first?.let { it / 1_000_000 } ?: "never"} ms after START, " +
                    "${back.size} steps backwards, $judders uneven steps of ${steps.size}, worst ${"%.1f".format(worstMs)} ms off"
            }
            val report = describe("steadied (what the screen draws)", steady) + "\n" + describe("raw speaker clock", raw) + "\n"
            File(dir(context), "clock.txt").writeText(report)
            Log.i(TAG, "clock:\n$report")
        }

        /** A hand clap, as in the unit tests: a bright burst of noise that dies away. */
        fun clap(seed: Int, peak: Float): FloatArray {
            val random = Random(seed)
            val attack = (RATE * 0.0003).toInt()
            val out = FloatArray(RATE * 60 / 1000) { i ->
                val t = i.toDouble() / RATE
                val env = if (i < attack) i.toDouble() / attack else exp(-(t - attack.toDouble() / RATE) / 0.012)
                ((random.nextDouble() * 2 - 1) * env).toFloat()
            }
            val loudest = out.maxOf { abs(it) }
            return FloatArray(out.size) { out[it] / loudest * peak }
        }

        /**
         * A player claps along with ten seconds of the metronome, through its own speaker: the
         * click reaches the microphone at a quarter of its level, and the player is a few
         * milliseconds late, give or take. The Ear's detector and judge go over it as they
         * would live, and the report sets what they made of each clap against the truth.
         */
        fun checkEar(context: Context, label: Paint, trace: Paint) {
            val groove = Groove(bpm = 120, kit = Kit.BLIP, subdivision = Subdivision.EIGHTHS, volume = 0.8f)
            val frames = RATE * 10
            val take = Bounce.render(groove, RATE, frames)
            val sounds = Synth.render(groove.kit, RATE)
            val gain = groove.volume * groove.volume
            val echo = 0.25f
            val random = Random(3)
            val heard = FloatArray(frames) { ((random.nextFloat() * 2 - 1) * 0.003f) }
            for (i in 0 until frames - 20) heard[i + 20] += take.audio[i] * echo
            // Claps on the beats only, from the second bar, with a few left out.
            val truth = ArrayList<Pair<Int, Double>>()
            take.ticks.filter { it.second.isBeat && it.second.bar > 0 }.forEachIndexed { n, (frame, _) ->
                if (n % 7 == 3) return@forEachIndexed
                var g = 0.0
                repeat(6) { g += random.nextDouble() }
                val offset = 6.0 + (g - 3.0) / 0.7071 * 7.0
                val at = frame + (offset * RATE / 1000).roundToInt()
                val sound = clap(n, 0.5f + 0.25f * random.nextFloat())
                for (i in sound.indices) if (at + i in heard.indices) heard[at + i] += sound[i]
                truth += at to offset
            }
            val detector = OnsetDetector(RATE)
            take.ticks.forEach { (frame, tick) ->
                val level = sounds.peak(tick) * gain
                if (level > 0f) detector.shadow(frame + 20L - RATE * 6 / 1000, frame + 20L + RATE * 30 / 1000, echo * level * 2.5f)
            }
            val onsets = ArrayList<Onset>()
            val block = FloatArray(240)
            var at = 0
            while (at < frames) {
                val n = minOf(block.size, frames - at)
                System.arraycopy(heard, at, block, 0, n)
                detector.process(block, n) { onsets += it }
                at += n
            }
            val judge = Judge(groove.bpm)
            val clicks = take.ticks.map { (frame, tick) -> Click(frame * 1_000_000_000L / RATE, tick) }
            val errors = ArrayList<Double>()
            onsets.forEach { onset ->
                val nanos = onset.frame * 1_000_000_000L / RATE
                val judgment = judge.judge(nanos, clicks.lastOrNull { it.nanos <= nanos }, clicks.firstOrNull { it.nanos > nanos }) ?: return@forEach
                val clap = truth.minByOrNull { abs(it.first - onset.frame) }
                if (clap != null) errors += judgment.offsetMillis - clap.second
            }
            val card = judge.card
            val worst = errors.maxOfOrNull { abs(it) } ?: Double.NaN
            val ok = onsets.size == truth.size && worst <= 1.5
            val report = buildString {
                append("${if (ok) "ok  " else "OFF "} ${truth.size} claps over the click's echo, ${onsets.size} heard; ")
                append("each placed within ${"%.2f".format(worst)} ms of the truth\n")
                append("     played ${"%.1f".format(truth.map { it.second }.average())} ms late on average, ")
                append("judged ${"%.1f".format(card.all.meanMillis)} ms (${card.all.verdict}), spread ±${"%.1f".format(card.all.spreadMillis)} ms\n")
                append("     rank ${card.rank}, ${card.points} points, best combo ${card.bestCombo}\n")
            }
            File(dir(context), "ear.txt").writeText(report)
            Log.i(TAG, "ear:\n$report")

            // The first four seconds of it, with the clicks, the claps and what was heard.
            val w = 1260
            val h = 220
            val picture = Bitmap.createBitmap(w + 24, h + 24, Bitmap.Config.ARGB_8888)
            Canvas(picture).apply {
                drawColor(0xFF120D16.toInt())
                drawRect(12f, 12f, 12f + w, 12f + h, Paint().apply { color = TUBE })
                val shown = RATE * 4
                fun x(frame: Long) = 12f + frame.toFloat() / shown * w
                take.ticks.filter { it.first < shown && it.second.isBeat }.forEach { (frame, _) ->
                    drawLine(x(frame.toLong()), 44f, x(frame.toLong()), 12f + h, Paint().apply { color = BEAT_LINE; strokeWidth = 2f })
                }
                onsets.filter { it.frame < shown }.forEach { onset ->
                    drawLine(x(onset.frame), 44f, x(onset.frame), 12f + h, Paint().apply { color = SUB_LINE; strokeWidth = 2f })
                }
                trace(heard.copyOf(shown), 12f, 44f, w, h - 32, trace)
                drawText("CLICKS (RED), WHAT THE EAR HEARD (BLUE), OVER THE MICROPHONE'S SIGNAL", 22f, 38f, label)
            }
            savePng(context, "ear", picture)
        }

        /** The tempo finder, played drum loops of known tempo. */
        fun checkTempo(context: Context) {
            val kit = Synth.render(Kit.DRUMS, RATE)
            val report = StringBuilder()
            for (bpm in listOf(84.0, 97.0, 123.0, 128.5, 140.0, 172.0)) {
                val random = Random(bpm.toInt())
                val loop = FloatArray(RATE * 12) { ((random.nextFloat() * 2 - 1) * 0.01f) }
                val beat = RATE * 60.0 / bpm
                var n = 0
                while (n * beat < loop.size) {
                    val at = (n * beat).toInt()
                    listOf(
                        at to (if (n % 2 == 0) kit.strong else kit.normal) to 0.7f,
                        at to kit.sub to 0.5f,
                        (at + beat / 2).toInt() to kit.sub to 0.35f,
                    ).forEach { (where, gain) ->
                        val (start, sound) = where
                        for (i in sound.indices) if (start + i < loop.size) loop[start + i] += sound[i] * gain
                    }
                    n++
                }
                val finder = TempoFinder(RATE)
                val block = FloatArray(240)
                var at = 0
                while (at + block.size <= loop.size) {
                    System.arraycopy(loop, at, block, 0, block.size)
                    finder.feed(block, block.size)
                    at += block.size
                }
                val guess = finder.estimate()
                val ok = guess != null && abs(guess.bpm - bpm) <= 0.5
                report.append("${if (ok) "ok  " else "OFF "} a loop at $bpm heard as ${guess?.let { "%.2f".format(it.bpm) } ?: "nothing"}, sure ${guess?.let { "%.2f".format(it.confidence) } ?: "-"}\n")
            }
            File(dir(context), "tempo.txt").writeText(report.toString())
            Log.i(TAG, "tempo:\n$report")
        }

        /** Draws [audio] as a trace in a [w]×[h] cell, loudest point per column. */
        fun Canvas.trace(audio: FloatArray, left: Float, top: Float, w: Int, h: Int, paint: Paint) {
            val mid = top + h / 2f
            val perColumn = (audio.size.toFloat() / w).coerceAtLeast(1f)
            for (x in 0 until w) {
                val from = (x * perColumn).toInt()
                val to = minOf(audio.size, ((x + 1) * perColumn).toInt().coerceAtLeast(from + 1))
                var lo = 0f
                var hi = 0f
                for (i in from until to) {
                    lo = minOf(lo, audio[i])
                    hi = maxOf(hi, audio[i])
                }
                drawLine(left + x, mid - hi * h / 2f, left + x, mid - lo * h / 2f + 1f, paint)
            }
        }

        fun renderGallery(context: Context) {
            val typeface = font(context)
            val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = INK
                textSize = 30f
                this.typeface = typeface
            }
            val trace = Paint().apply {
                color = TRACE
                strokeWidth = 1f
            }

            // Every sound of every kit.
            val cellW = 420
            val cellH = 170
            val gap = 12
            val kits = Bitmap.createBitmap(4 * (cellW + gap) + gap, Kit.entries.size * (cellH + gap) + gap, Bitmap.Config.ARGB_8888)
            Canvas(kits).apply {
                drawColor(0xFF120D16.toInt())
                Kit.entries.forEachIndexed { row, kit ->
                    val sounds = Synth.render(kit, RATE)
                    listOf("accent" to sounds.strong, "beat" to sounds.normal, "between" to sounds.sub, "cross" to sounds.cross).forEachIndexed { col, (name, audio) ->
                        val left = (gap + col * (cellW + gap)).toFloat()
                        val top = (gap + row * (cellH + gap)).toFloat()
                        drawRect(left, top, left + cellW, top + cellH, Paint().apply { color = TUBE })
                        // A fixed time scale (half a second across), so lengths compare.
                        val window = FloatArray(RATE / 2).also { audio.copyInto(it, 0, 0, minOf(audio.size, it.size)) }
                        trace(window, left, top + 30f, cellW, cellH - 34, trace)
                        drawText("${kit.label} · $name · ${audio.size * 1000 / RATE} ms".uppercase(), left + 10f, top + 28f, label)
                    }
                }
            }
            savePng(context, "kits", kits)

            // A few grooves rendered through the live engine's sequencer and mixer.
            val grooves = listOf(
                "4/4 120 blip" to (Groove(bpm = 120, kit = Kit.BLIP) to Practice()),
                "3/4 90 wood, 8ths" to (Groove(bpm = 90, kit = Kit.WOOD, subdivision = Subdivision.EIGHTHS).withMeter(Meter.THREE_FOUR) to Practice()),
                "6/8 150 cowbell" to (Groove(bpm = 150, kit = Kit.COWBELL).withMeter(Meter.SIX_EIGHT) to Practice()),
                "4/4 100 drums, 16ths" to (Groove(bpm = 100, kit = Kit.DRUMS, subdivision = Subdivision.SIXTEENTHS) to Practice()),
                "4/4 110 beep, swing, ghost + rest" to (
                    Groove(
                        bpm = 110,
                        kit = Kit.BEEP,
                        subdivision = Subdivision.SWING,
                        accents = listOf(Accent.STRONG, Accent.SOFT, Accent.MUTE, Accent.NORMAL),
                    ) to Practice()
                    ),
                "4/4 240 blip, 1 bar on 1 bar silent" to (Groove(bpm = 240) to Practice(gap = true, gapPlay = 1, gapRest = 1)),
                "4/4 200 wood, ramp +20 every bar" to (Groove(bpm = 200, kit = Kit.WOOD) to Practice(ramp = true, rampStep = 20, rampEvery = 1, rampTarget = 300)),
                "4/4 90 drums, 3 against 4" to (Groove(bpm = 90, kit = Kit.DRUMS, cross = 3) to Practice()),
                "5/4 160 wind-up, 30% dropout" to (Groove(bpm = 160, kit = Kit.WIND_UP).withMeter(Meter.FIVE_FOUR) to Practice(dropout = 30)),
            )
            val rowW = 1260
            val rowH = 150
            val bars = Bitmap.createBitmap(rowW + 2 * gap, grooves.size * (rowH + gap) + gap, Bitmap.Config.ARGB_8888)
            val report = StringBuilder()
            Canvas(bars).apply {
                drawColor(0xFF120D16.toInt())
                grooves.forEachIndexed { row, (name, setup) ->
                    val (groove, practice) = setup
                    val frames = RATE * 4
                    val take = Bounce.render(groove, RATE, frames, practice)
                    val left = gap.toFloat()
                    val top = (gap + row * (rowH + gap)).toFloat()
                    drawRect(left, top, left + rowW, top + rowH, Paint().apply { color = TUBE })
                    take.ticks.forEach { (frame, tick) ->
                        val x = left + frame.toFloat() / frames * rowW
                        val line = Paint().apply {
                            color = when {
                                tick.isCross -> CROSS_LINE
                                tick.step == 0 -> BEAT_LINE
                                else -> SUB_LINE
                            }
                            strokeWidth = if (tick.isDownbeat) 3f else 1f
                            alpha = if (tick.audible) 255 else 90
                        }
                        drawLine(x, top + 30f, x, top + rowH, line)
                    }
                    trace(take.audio, left, top + 30f, rowW, rowH - 34, trace)
                    drawText(name.uppercase(), left + 10f, top + 28f, label)

                    // How late each audible click starts after its scheduled frame.
                    var worst = 0
                    var silentBefore = 0
                    var checked = 0
                    take.ticks.filter { it.second.audible && !(it.second.isBeat && it.second.accent == Accent.MUTE) }.forEach { (frame, _) ->
                        val onset = (frame until minOf(frames, frame + RATE / 20)).firstOrNull { abs(take.audio[it]) > 0.003f }
                        if (onset != null) {
                            worst = maxOf(worst, onset - frame)
                            checked++
                        }
                        if (frame > 0 && take.audio[frame - 1] == 0f) silentBefore++
                    }
                    val ok = worst <= RATE / 1000
                    report.append(
                        "${if (ok) "ok  " else "LATE"} $name: ${take.ticks.size} clicks, $checked heard, " +
                            "latest onset ${"%.2f".format(worst * 1000f / RATE)} ms, $silentBefore start from silence\n",
                    )
                }
            }
            savePng(context, "bars", bars)
            File(dir(context), "timing.txt").writeText(report.toString())
            Log.i(TAG, "timing:\n$report")

            checkEar(context, label, trace)
            checkTempo(context)

            // Two bars of each kit, to listen to.
            val sounds = dir(context, "sounds")
            Kit.entries.forEach { kit ->
                val groove = Groove(bpm = 100, kit = kit, subdivision = Subdivision.EIGHTHS, volume = 1f)
                saveWav(File(sounds, "${kit.label}.wav"), Bounce.render(groove, RATE, (RATE * 4.8).toInt()).audio)
            }
            Log.i(TAG, "gallery done")
        }
    }
}
