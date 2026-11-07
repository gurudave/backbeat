package app.djungle.backbeat.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.djungle.backbeat.data.AppSettings
import app.djungle.backbeat.data.Logbook
import app.djungle.backbeat.data.Options
import app.djungle.backbeat.data.Tape
import app.djungle.backbeat.engine.Groove
import app.djungle.backbeat.engine.Kit
import app.djungle.backbeat.engine.MAX_BPM
import app.djungle.backbeat.engine.MIN_BPM
import app.djungle.backbeat.engine.Meter
import app.djungle.backbeat.engine.Practice
import app.djungle.backbeat.engine.Subdivision
import app.djungle.backbeat.ui.theme.LocalVibe
import app.djungle.backbeat.ui.theme.Vcr
import app.djungle.backbeat.ui.theme.Vibe
import kotlinx.coroutines.delay
import java.time.LocalDate

private fun bars(n: Int) = if (n == 1) "1 bar" else "$n bars"

@Composable
private fun Hint(text: String) {
    Text(
        text,
        color = LocalVibe.current.ink.copy(alpha = 0.6f),
        fontFamily = Vcr,
        fontSize = 18.sp,
        lineHeight = 20.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
    )
}

@Composable
private fun Title(text: String) = SectionTitle(text, Modifier.padding(horizontal = 20.dp))

/** What is played: time signature, the clicks between beats, the sound and its volume. */
@Composable
fun BeatPanel(groove: Groove, onChange: (Groove) -> Unit, onKit: (Kit) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Title("time signature")
        ChipRow(Meter.entries, groove.meter, { it.label }) { onChange(groove.withMeter(it)) }
        Title("between the beats")
        ChipRow(Subdivision.entries, groove.subdivision, { it.label }) { onChange(groove.copy(subdivision = it)) }
        Title("cross-rhythm")
        // Against four beats, four is no cross-rhythm at all.
        val crosses = listOf(0) + (2..AppSettings.MAX_CROSS).filter { it != groove.beats }
        ChipRow(crosses, groove.crossCount, { if (it == 0) "off" else "$it:${groove.beats}" }) { onChange(groove.copy(cross = it)) }
        Title("sound")
        ChipRow(Kit.entries, groove.kit, { it.label }, onKit)
        Title("volume")
        SegmentBar(
            value = groove.volume,
            min = 0.1f,
            steps = 10,
            label = "volume",
            onChange = { onChange(groove.copy(volume = it)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        Hint(
            "tap a light on the screen to change its beat: accent, beat, ghost or rest. the tallest light is the loudest. " +
                "a cross-rhythm spreads its clicks evenly over the bar, in a sound of its own: 3:4 is three against four.",
        )
    }
}

/** The practice aids: a climbing tempo, silent bars, beats that go missing and a timer, under the logbook. */
@Composable
fun PracticePanel(practice: Practice, groove: Groove, onChange: (Practice) -> Unit, logbook: Map<String, Int> = emptyMap()) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Title("logbook")
        LogbookView(logbook, LocalDate.now(), Modifier.padding(horizontal = 16.dp))
        Box(Modifier.height(4.dp))
        ToggleRow("tempo ramp", "speeds up (or slows down) a little every few bars", practice.ramp) { on ->
            // Turning it on with the target where you already are would do nothing, so aim higher.
            val target = if (on && practice.rampTarget == groove.bpm) (groove.bpm + 20).coerceAtMost(MAX_BPM) else practice.rampTarget
            onChange(practice.copy(ramp = on, rampTarget = target))
        }
        if (practice.ramp) {
            val sign = if (practice.rampTarget < groove.bpm) "-" else "+"
            StepperRow("by", practice.rampStep, 1..20, { onChange(practice.copy(rampStep = it)) }) { "$sign$it bpm" }
            StepperRow("every", practice.rampEvery, 1..16, { onChange(practice.copy(rampEvery = it)) }, ::bars)
            StepperRow("until", practice.rampTarget, MIN_BPM..MAX_BPM, { onChange(practice.copy(rampTarget = it)) }) { "$it bpm" }
        }
        ToggleRow("silent bars", "the click drops out, and you keep time on your own", practice.gap) {
            onChange(practice.copy(gap = it))
        }
        if (practice.gap) {
            StepperRow("play", practice.gapPlay, 1..16, { onChange(practice.copy(gapPlay = it)) }, ::bars)
            StepperRow("then hush", practice.gapRest, 1..16, { onChange(practice.copy(gapRest = it)) }, ::bars)
        }
        Box(Modifier.height(4.dp))
        Title("dropout")
        ChipRow(listOf(0, 10, 20, 30, 50), practice.dropout, { if (it == 0) "off" else "$it%" }) {
            onChange(practice.copy(dropout = it))
        }
        Hint("beats go missing at random, for you to fill in. the one always plays.")
        Box(Modifier.height(4.dp))
        Title("stop after")
        ChipRow(listOf(0, 5, 10, 15, 20, 30, 45, 60), practice.stopAfterMinutes, { if (it == 0) "never" else "$it min" }) {
            onChange(practice.copy(stopAfterMinutes = it))
        }
        Hint("it stops at the end of a bar, never mid-phrase.")
    }
}

/** Saved set-ups, each a cassette with a label to write a song's name on. */
@Composable
fun TapesPanel(
    tapes: List<Tape>,
    groove: Groove,
    playing: Boolean,
    onRecord: () -> Unit,
    onLoad: (Tape) -> Unit,
    onRename: (Tape, String) -> Unit,
    onErase: (Tape) -> Unit,
    onRaise: (Tape) -> Unit = {},
) {
    val vibe = LocalVibe.current
    val blink = rememberBlink(700L)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            RetroBlock(
                fill = vibe.surface,
                onClick = onRecord,
                depth = 3.dp,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RecLight(blink)
                    Text("  REC THIS TAPE", color = vibe.ink, fontFamily = Vcr, fontSize = 20.sp)
                }
            }
        }
        if (tapes.isEmpty()) {
            Hint("no tapes yet. set up a tempo you like, press rec, and write the song's name on its label.")
        }
        tapes.forEachIndexed { i, tape ->
            TapeRow(
                tape = tape,
                loaded = tape.groove == groove,
                spinning = playing && tape.groove == groove,
                onLoad = { onLoad(tape) },
                onRename = { onRename(tape, it) },
                onErase = { onErase(tape) },
                onRaise = if (i > 0) ({ onRaise(tape) }) else null,
            )
        }
        if (tapes.size > 1) {
            Hint("the tapes are a set list: on the big screen, or with a pedal, step from one to the next. ^ moves a tape up.")
        }
    }
}

@Composable
private fun TapeRow(
    tape: Tape,
    loaded: Boolean,
    spinning: Boolean,
    onLoad: () -> Unit,
    onRename: (String) -> Unit,
    onErase: () -> Unit,
    onRaise: (() -> Unit)?,
) {
    val vibe = LocalVibe.current
    var arming by remember { mutableStateOf(false) }
    LaunchedEffect(arming) {
        if (arming) {
            delay(3000)
            arming = false
        }
    }
    RetroBlock(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        fill = vibe.surface,
        border = if (loaded) vibe.accent else blockShadow(),
        depth = 4.dp,
        contentPadding = PaddingValues(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Reels(spinning)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                BasicTextField(
                    value = tape.name,
                    onValueChange = { onRename(it.take(28)) },
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = Vcr, fontSize = 22.sp, color = vibe.ink),
                    cursorBrush = SolidColor(vibe.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { Pedal.typing = it.isFocused }
                        .semantics { contentDescription = "tape label" },
                )
                val cross = if (tape.groove.crossCount > 0) " · ${tape.groove.crossCount}:${tape.groove.beats}" else ""
                Text(
                    "${tape.groove.bpm} BPM · ${tape.groove.meter.label}$cross · ${tape.groove.subdivision.label} · ${tape.groove.kit.label}".uppercase(),
                    color = vibe.ink.copy(alpha = 0.6f),
                    fontFamily = Vcr,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ArcadeStripes(vibe.stripes, Modifier.fillMaxWidth().padding(top = 4.dp), band = 2.dp, gap = 0.dp)
            }
            if (onRaise != null) {
                RetroChip("^", selected = false, onClick = onRaise, modifier = Modifier.semantics { contentDescription = "move up" })
                Box(Modifier.width(4.dp))
            }
            RetroChip("load", selected = loaded, onClick = onLoad)
            Box(Modifier.width(4.dp))
            RetroChip(if (arming) "sure?" else "x", selected = arming, onClick = { if (arming) onErase() else arming = true })
        }
    }
}

/** The window of a cassette, its two reels turning while the tape is playing. */
@Composable
private fun Reels(spinning: Boolean) {
    val vibe = LocalVibe.current
    val turn by rememberInfiniteTransition(label = "reels").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
        label = "turn",
    )
    val angle = if (spinning) turn else 0f
    Canvas(Modifier.size(width = 52.dp, height = 30.dp)) {
        val stroke = 2.dp.toPx()
        drawRoundRect(vibe.background, cornerRadius = CornerRadius(6.dp.toPx()))
        drawRoundRect(vibe.ink.copy(alpha = 0.5f), cornerRadius = CornerRadius(6.dp.toPx()), style = Stroke(stroke))
        val r = size.height * 0.3f
        listOf(size.width * 0.28f, size.width * 0.72f).forEach { x ->
            val c = Offset(x, size.height / 2f)
            drawCircle(vibe.ink.copy(alpha = 0.85f), r, c, style = Stroke(stroke))
            rotate(angle, c) {
                for (k in 0 until 3) {
                    rotate(k * 120f, c) {
                        drawRect(vibe.ink.copy(alpha = 0.85f), Offset(c.x - stroke / 2f, c.y - r), Size(stroke, r * 0.7f))
                    }
                }
            }
        }
    }
}

/** The look of the app, and how it behaves. */
@Composable
fun SetUpPanel(vibe: Vibe, onVibe: (Vibe) -> Unit, options: Options, onOptions: (Options) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ChannelPicker(vibe, onVibe, Modifier.padding(horizontal = 16.dp))
        Box(Modifier.height(4.dp))
        ToggleRow("screen flash", "the picture blinks on the one, for loud rooms", options.flash) {
            onOptions(options.copy(flash = it))
        }
        ToggleRow("buzz", "the phone taps along on every beat", options.buzz) {
            onOptions(options.copy(buzz = it))
        }
        ToggleRow("keep ticking", "carries on with the screen off or in another app", options.keepTicking) {
            onOptions(options.copy(keepTicking = it))
        }
    }
}

/** "23 MIN", or "4 H 05" once it runs to hours. */
fun duration(seconds: Long): String {
    val minutes = seconds / 60
    return if (minutes < 60) "$minutes MIN" else "${minutes / 60} H " + (minutes % 60).toString().padStart(2, '0')
}

/**
 * The logbook: twelve weeks of practice as a grid of pixels, a column a week and Monday at
 * the top, brighter for longer, beside today's time, the streak and the total.
 */
@Composable
fun LogbookView(days: Map<String, Int>, today: LocalDate, modifier: Modifier = Modifier) {
    val vibe = LocalVibe.current
    val summary = Logbook.summarise(days, today)
    val grid = Logbook.grid(days, today)
    RetroBlock(modifier.fillMaxWidth(), fill = vibe.background, contentPadding = PaddingValues(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Canvas(
                Modifier
                    .size(width = (grid.size * 13).dp, height = (7 * 13).dp)
                    .semantics { contentDescription = "practice over the last twelve weeks" },
            ) {
                val cell = size.width / grid.size
                val side = cell - 3.dp.toPx()
                grid.forEachIndexed { w, week ->
                    week.forEachIndexed { d, seconds ->
                        val at = Offset(w * cell, d * cell)
                        when {
                            seconds == null -> Unit
                            seconds < 60 -> drawRect(vibe.ink.copy(alpha = 0.1f), at, Size(side, side))
                            else -> {
                                val minutes = seconds / 60
                                val color = when {
                                    minutes < 10 -> vibe.primary.copy(alpha = 0.35f)
                                    minutes < 20 -> vibe.primary.copy(alpha = 0.6f)
                                    minutes < 40 -> vibe.primary
                                    else -> vibe.accent
                                }
                                drawRect(color, at, Size(side, side))
                            }
                        }
                        if (w == grid.lastIndex && d == today.dayOfWeek.value - 1) {
                            drawRect(vibe.ink, at, Size(side, side), style = Stroke(1.5.dp.toPx()))
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                LogLine("today", duration(summary.todaySeconds.toLong()))
                LogLine("streak", if (summary.streak == 1) "1 DAY" else "${summary.streak} DAYS")
                LogLine("in all", duration(summary.totalSeconds))
            }
        }
    }
}

@Composable
private fun LogLine(label: String, value: String) {
    val vibe = LocalVibe.current
    Text(label.uppercase(), color = vibe.ink.copy(alpha = 0.6f), fontFamily = Vcr, fontSize = 16.sp, lineHeight = 16.sp)
    Text(value, color = vibe.accent, fontFamily = Vcr, fontSize = 22.sp, lineHeight = 22.sp, maxLines = 1)
}
