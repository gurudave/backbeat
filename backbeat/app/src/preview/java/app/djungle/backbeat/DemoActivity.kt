package app.djungle.backbeat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.content.res.ResourcesCompat
import app.djungle.backbeat.engine.Accent
import app.djungle.backbeat.engine.Bounce
import app.djungle.backbeat.engine.Groove
import app.djungle.backbeat.engine.Kit
import app.djungle.backbeat.engine.Meter
import app.djungle.backbeat.engine.Metronome
import app.djungle.backbeat.engine.Now
import app.djungle.backbeat.engine.Practice
import app.djungle.backbeat.engine.Subdivision
import app.djungle.backbeat.engine.Synth
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Renders pictures and sounds of the engine for CI to look over (mode=gallery, the default):
 *   kits.png        every sound of every kit, as an oscilloscope trace
 *   bars.png        a few bars of several grooves, with a line where each click was scheduled
 *   timing.txt      for every click in those bars, how long after its line the sound starts
 *   sounds/KIT.wav  two bars of each kit, to listen to
 *
 * And checks the ball's clock against the emulator's real sound card (mode=clock):
 *   clock.txt       how smoothly the ball would move for the first seconds after START
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
            val kits = Bitmap.createBitmap(3 * (cellW + gap) + gap, Kit.entries.size * (cellH + gap) + gap, Bitmap.Config.ARGB_8888)
            Canvas(kits).apply {
                drawColor(0xFF120D16.toInt())
                Kit.entries.forEachIndexed { row, kit ->
                    val sounds = Synth.render(kit, RATE)
                    listOf("accent" to sounds.strong, "beat" to sounds.normal, "between" to sounds.sub).forEachIndexed { col, (name, audio) ->
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
                            color = if (tick.step == 0) BEAT_LINE else SUB_LINE
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
                    take.ticks.filter { it.second.audible && !(it.second.step == 0 && it.second.accent == Accent.MUTE) }.forEach { (frame, _) ->
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
