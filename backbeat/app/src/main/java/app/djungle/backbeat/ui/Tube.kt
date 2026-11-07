package app.djungle.backbeat.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import app.djungle.backbeat.engine.Accent
import app.djungle.backbeat.engine.EarMode
import app.djungle.backbeat.engine.EarState
import app.djungle.backbeat.engine.Grade
import app.djungle.backbeat.engine.Groove
import app.djungle.backbeat.engine.Judgment
import app.djungle.backbeat.engine.Now
import app.djungle.backbeat.engine.Practice
import app.djungle.backbeat.engine.Scorecard
import app.djungle.backbeat.engine.tempoMarking
import app.djungle.backbeat.ui.theme.Arcade
import app.djungle.backbeat.ui.theme.LocalVibe
import app.djungle.backbeat.ui.theme.Vcr
import kotlin.math.abs
import kotlin.math.roundToInt

/** How tall a beat light stands for each accent, as a share of the tallest. */
private fun Accent.height(): Float = when (this) {
    Accent.STRONG -> 1f
    Accent.NORMAL -> 0.66f
    Accent.SOFT -> 0.4f
    Accent.MUTE -> 0.16f
}

/** What the television shows of the Ear: what it is doing, and a session's hits and score. */
class EarShow(val state: EarState, val hits: State<List<Judgment>>, val live: State<Scorecard>) {
    val coaching: Boolean get() = state.mode == EarMode.COACH
}

/** The colour of a grade on the picture: bright on a dark tube, deeper on a light one. */
fun gradeColor(grade: Grade, dark: Boolean): Color = when (grade) {
    Grade.PERFECT -> if (dark) Color(0xFF3FE0FF) else Color(0xFF0B7A96)
    Grade.GREAT -> if (dark) Color(0xFF7CFF4F) else Color(0xFF2E7D22)
    Grade.GOOD -> if (dark) Color(0xFFFFC233) else Color(0xFFA06C00)
    Grade.MISS -> if (dark) Color(0xFFFF4D4D) else Color(0xFFC0302A)
}

/** "+3 MS" or "-12 MS". */
fun signedMillis(millis: Float): String {
    val whole = millis.roundToInt()
    return (if (whole > 0) "+$whole" else "$whole") + " MS"
}

/**
 * The television: the tempo in big marquee digits, the time signature, bar and tape counter
 * in VCR lettering, and a row of beat lights with a pixel ball bouncing along them.
 *
 * [fold] runs from 0 (the full picture) to 1 (folded into a strip, to make room for the
 * settings): the digits fade away, the tempo moves up into the top line, and the lights
 * crouch a little lower.
 *
 * While the Ear listens ([ear]) the picture changes with it: playing along, a strip in the
 * top line shows each hit early or late of the click; finding a tempo, the digits show what
 * it hears and the lights become a level meter.
 *
 * [now] changes every frame while playing. It is only read while drawing, or through
 * [derivedStateOf], so the screen redraws each frame without recomposing.
 */
@Composable
fun Tube(
    groove: Groove,
    practice: Practice,
    playing: Boolean,
    liveBpm: Int?,
    now: State<Now?>,
    counterSeconds: Long,
    flash: Boolean,
    onAccent: (Int) -> Unit,
    height: Dp,
    fold: Float = 0f,
    ear: EarShow? = null,
    modifier: Modifier = Modifier,
) {
    val vibe = LocalVibe.current
    val blink = rememberBlink(520L)
    // The digits get whatever height the top line and the lights leave. Short of room, they
    // fade out and the tempo moves up into the top line instead.
    val lightsBox = lerp(LIGHTS_BOX, LIGHTS_BOX_FOLDED, fold)
    val digitsRoom = height - TOP_LINE - 16.dp - lightsBox
    val digitsShown = ((digitsRoom - DIGITS_GONE) / DIGITS_FADE).coerceIn(0f, 1f)
    val folded = digitsShown < 0.5f
    // The digits are gone before the tempo appears in the top line, so it is never shown twice.
    val digitsAlpha = ((digitsShown - 0.5f) * 2f).coerceIn(0f, 1f)
    val bar by remember { derivedStateOf { now.value?.beat?.bar } }
    val silent by remember { derivedStateOf { now.value?.beat?.audible == false } }
    val bpm = if (playing) liveBpm ?: groove.bpm else groove.bpm
    val mode = ear?.state?.mode ?: EarMode.OFF
    val listening = mode == EarMode.TEMPO
    val coaching = mode == EarMode.COACH && playing

    RetroBlock(modifier.fillMaxWidth(), contentPadding = PaddingValues(10.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(18.dp))
                .then(rememberTubeEffects())
                .background(vibe.soft)
                .drawBehind {
                    // "Screen flash": the picture blinks in the accent colour on each downbeat.
                    val n = now.value
                    if (flash && n != null && n.beat.isDownbeat && n.beat.audible) {
                        val fade = (1f - n.sinceBeatMillis / 160f).coerceIn(0f, 1f)
                        if (fade > 0f) drawRect(vibe.accent.copy(alpha = 0.55f * fade))
                    }
                },
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth().height(TOP_LINE), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        listening -> {
                            RecLight(blink)
                            Text("  EAR · LISTENING", style = osdStyle(20.sp), maxLines = 1)
                        }
                        else -> Text(
                            if (folded) {
                                "$bpm BPM  ${groove.meter.label}" + if (groove.crossCount > 0) " ${groove.crossCount}:${groove.beats}" else ""
                            } else {
                                "${groove.meter.label}  " +
                                    if (groove.crossCount > 0) "${groove.crossCount} AGAINST ${groove.beats}" else groove.subdivision.label.uppercase()
                            },
                            style = osdStyle(20.sp),
                            maxLines = 1,
                        )
                    }
                    Box(Modifier.weight(1f))
                    val counter = bar
                    when {
                        listening -> Text(tapeCounter(ear?.state?.heardSeconds?.toLong() ?: 0L), style = osdStyle(20.sp), maxLines = 1)
                        coaching && ear != null -> TimingStrip(ear.hits, now, Modifier.width(TIMING_STRIP_WIDTH).height(14.dp))
                        mode == EarMode.CALIBRATE -> Text("CAL", style = osdStyle(20.sp, if (blink) Color.White else Color.Transparent), maxLines = 1)
                        playing -> {
                            val barText = "BAR " + ((counter ?: 0) + 1).toString().padStart(3, '0')
                            Text(if (folded) barText else "$barText  ${tapeCounter(counterSeconds)}", style = osdStyle(20.sp), maxLines = 1)
                        }
                        else -> Text("PRESS START", style = osdStyle(20.sp, if (blink) Color.White else Color.Transparent), maxLines = 1)
                    }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds(), contentAlignment = Alignment.Center) {
                    val room = maxWidth
                    val tall = maxHeight - STATUS_HEIGHT
                    if (digitsAlpha > 0f) Column(Modifier.graphicsLayer { alpha = digitsAlpha }, horizontalAlignment = Alignment.CenterHorizontally) {
                        if (listening) {
                            val guess = ear?.state?.tempo
                            val found = ear?.state?.found == true
                            BigTempo(guess?.rounded?.toString() ?: "---", room, tall, if (found || blink) 1f else 0.55f)
                            Text(
                                when {
                                    found -> "BPM · FOUND IT · PRESS SET"
                                    guess != null -> "BPM? · KEEP IT PLAYING"
                                    else -> "PLAY SOME MUSIC NEAR THE PHONE"
                                },
                                style = osdStyle(20.sp),
                                maxLines = 1,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        } else {
                            BigTempo(bpm.toString(), room, tall)
                            if (coaching && ear != null) {
                                CoachStatus(ear)
                            } else {
                                val status = when {
                                    mode == EarMode.CALIBRATE -> if (blink) "CALIBRATING · LISTENING TO ITSELF" else ""
                                    playing && silent -> if (blink) "· SILENT BAR ·" else ""
                                    playing && practice.ramp && bpm != practice.rampTarget -> "BPM · RAMP TO ${practice.rampTarget}"
                                    else -> "BPM · " + tempoMarking(bpm).uppercase()
                                }
                                Text(status, style = osdStyle(20.sp), maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
                if (listening) {
                    LevelMeter(ear?.state?.level ?: 0f, Modifier.fillMaxWidth().height(lightsBox).padding(top = lightsBox * 0.4f, bottom = 8.dp))
                } else {
                    BeatLights(groove, now, onAccent, lightsBox, ear?.takeIf { coaching }?.hits)
                }
            }
            Box(Modifier.matchParentSize().scanlines(Color.Black.copy(alpha = if (vibe.isDark) 0.16f else 0.05f)))
        }
    }
}

/** The line under the digits while playing along: the last hit's grade, or the score. */
@Composable
private fun CoachStatus(ear: EarShow) {
    val vibe = LocalVibe.current
    val last = ear.hits.value.lastOrNull()
    val live = ear.live.value
    val fresh = last != null && System.nanoTime() - last.nanos < 1_500_000_000L
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (fresh && last != null) {
            Text(last.grade.label.uppercase(), style = osdStyle(20.sp, gradeColor(last.grade, vibe.isDark)), maxLines = 1)
            Text(
                "  ${signedMillis(last.offsetMillis)}" + if (last.combo >= 2) "  COMBO ${last.combo}" else "",
                style = osdStyle(20.sp),
                maxLines = 1,
            )
        } else {
            Text(
                if (live.hits == 0) "PLAY ALONG · IT'S LISTENING" else "SCORE " + live.points.toString().padStart(6, '0'),
                style = osdStyle(20.sp),
                maxLines = 1,
            )
        }
    }
}

/** Room left under the digits for the line that names the tempo. */
private val STATUS_HEIGHT = 30.dp

/** The line across the top of the picture: time signature, bar and tape counter. */
private val TOP_LINE = 24.dp

/** Below this height the digits are not drawn at all; they fade in over the next [DIGITS_FADE]. */
private val DIGITS_GONE = 40.dp
private val DIGITS_FADE = 30.dp

/** The least height for the whole picture with its digits, and no folding. */
val TUBE_UNFOLDED_MIN = 212.dp

/** The tape counter: 0:05, 12:34, or 1:02:03 after an hour. */
fun tapeCounter(seconds: Long): String =
    if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)

/** The height of the picture folded into a strip: one line of text over the lights. */
val TUBE_FOLDED = 124.dp

/** The box the lights and their ball sit in, unfolded and folded. */
private val LIGHTS_BOX = 96.dp
private val LIGHTS_BOX_FOLDED = 84.dp

/** The timing strip in the top line. */
private val TIMING_STRIP_WIDTH = 120.dp

/** The tempo in marquee digits, as big as [width] by [height] allows. */
@Composable
fun BigTempo(text: String, width: Dp, height: Dp, alpha: Float = 1f) {
    val vibe = LocalVibe.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = TextStyle(fontFamily = Arcade, fontSize = 56.sp)
    // Always sized for three digits, so the number does not jump about as it changes.
    val natural = remember(measurer, density) { measurer.measure("300", style, softWrap = false).size }
    // On a tablet's big picture the digits may grow; on a phone they stay at their natural size.
    val largest = if (width > 480.dp) 2.4f else 1f
    val scale = with(density) {
        minOf(largest, width.toPx() * 0.8f / natural.width, height.toPx() / natural.height)
    }.coerceAtLeast(0.3f)
    StripedText(text, style.copy(fontSize = 56.sp * scale), vibe.stripes, blockShadow(), Modifier.graphicsLayer { this.alpha = alpha })
}

/**
 * A ruler of early and late, the click in the middle and 50 ms either side, with the session's
 * latest hits on it. The newest is drawn tall in its grade's colour; older ones fade.
 */
@Composable
fun TimingStrip(hits: State<List<Judgment>>, now: State<Now?>, modifier: Modifier = Modifier) {
    val vibe = LocalVibe.current
    val dark = vibe.isDark
    Canvas(modifier.semantics { contentDescription = "how early or late each hit is" }) {
        // Read only so the strip is drawn afresh each frame, and its hits fade as they age.
        now.value
        val middle = size.width / 2f
        val perMilli = middle / STRIP_MILLIS
        drawRect(Color.Black.copy(alpha = if (dark) 0.45f else 0.12f))
        // The zones: good, great and perfect, brighter towards the middle.
        listOf(Grade.GOOD to 0.12f, Grade.GREAT to 0.16f, Grade.PERFECT to 0.22f).forEach { (grade, alpha) ->
            val half = grade.withinMillis * perMilli
            drawRect(Color.White.copy(alpha = if (dark) alpha else alpha * 0.8f), Offset(middle - half, 0f), Size(half * 2, size.height))
        }
        drawRect(Color.White, Offset(middle - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
        val list = hits.value
        val nowNanos = System.nanoTime()
        list.forEachIndexed { i, hit ->
            val age = (nowNanos - hit.nanos) / 1e9f
            val newest = i == list.lastIndex
            val alpha = (1f - age / 4f).coerceIn(0f, 1f)
            if (alpha <= 0f) return@forEachIndexed
            val x = middle + hit.offsetMillis.coerceIn(-STRIP_MILLIS, STRIP_MILLIS) * perMilli
            val w = (if (newest) 4.dp else 2.dp).toPx()
            val h = if (newest) size.height else size.height * 0.6f
            drawRect(gradeColor(hit.grade, dark).copy(alpha = alpha), Offset(x - w / 2f, (size.height - h) / 2f), Size(w, h))
        }
    }
}

/** Half the width of the timing strip, in milliseconds. */
private const val STRIP_MILLIS = 50f

/** The microphone's level, as a row of blocks like a tape deck's meter. */
@Composable
private fun LevelMeter(level: Float, modifier: Modifier) {
    val vibe = LocalVibe.current
    val shadow = blockShadow()
    Canvas(modifier.semantics { contentDescription = "microphone level" }) {
        val count = 16
        val gap = 3.dp.toPx()
        val w = (size.width - gap * (count - 1)) / count
        val lit = (level * count).roundToInt()
        for (i in 0 until count) {
            val h = size.height * (0.4f + 0.6f * i / (count - 1))
            val left = i * (w + gap)
            val color = when {
                i >= lit -> vibe.background.copy(alpha = 0.55f)
                i >= count - 3 -> Color(0xFFFF4D4D)
                i >= count - 6 -> vibe.accent
                else -> vibe.primary
            }
            drawRect(shadow, Offset(left + 2.dp.toPx(), size.height - h + 2.dp.toPx()), Size(w, h))
            drawRect(color, Offset(left, size.height - h), Size(w, h))
        }
    }
}

/**
 * One light per beat. Its height shows the beat's accent; tapping it steps to the next. The
 * light for the beat being heard is lit, small pips beneath it count the clicks between beats,
 * and a pixel ball hops from light to light, landing on each just as its beat sounds.
 *
 * With a cross-rhythm, a second row runs under the lights in place of the beat numbers: a
 * block for each of its clicks, and a cursor that sweeps across them, over each as it sounds.
 *
 * While playing along ([hits]), each hit marks its beat's light: a pip at the top, left of
 * centre for early and right for late, in the colour of its grade.
 */
@Composable
private fun BeatLights(groove: Groove, now: State<Now?>, onAccent: (Int) -> Unit, boxHeight: Dp, hits: State<List<Judgment>>?) {
    val vibe = LocalVibe.current
    val shadow = blockShadow()
    val beats = groove.beats
    val gap = if (beats > 8) 3.dp else 6.dp
    val steps = groove.subdivision.steps
    val cross = groove.crossCount
    Box(Modifier.fillMaxWidth().height(boxHeight)) {
        Row(
            Modifier.fillMaxWidth().height(LIGHTS_HEIGHT).align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            repeat(beats) { i ->
                val accent = groove.accent(i)
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics(mergeDescendants = true) {
                            contentDescription = "beat ${i + 1}: ${accent.label}. tap to change"
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                        ) { onAccent(i) },
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .drawBehind {
                                val n = now.value
                                val lit = n != null && n.beat.beat % beats == i
                                drawLight(accent, lit, n?.beat?.audible != false, shadow, vibe.accent, vibe.primary, vibe.ink, vibe.background)
                                if (hits != null && n != null) drawHitPips(hits.value, i, n.beat.bar, accent, vibe.isDark)
                            },
                    )
                    // A pip for each click within the beat.
                    Row(
                        Modifier.fillMaxWidth().height(5.dp).padding(end = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        repeat(steps) { s ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .drawBehind {
                                        val n = now.value
                                        val on = n != null && n.tick.beat % beats == i && n.tick.step == s
                                        drawRect(if (on) vibe.accent else vibe.ink.copy(alpha = 0.18f))
                                    },
                            )
                        }
                    }
                    if (cross > 0) {
                        Box(Modifier.fillMaxWidth().height(NUMBER_HEIGHT))
                    } else {
                        Text(
                            (i + 1).toString(),
                            color = vibe.ink.copy(alpha = 0.7f),
                            fontFamily = Vcr,
                            fontSize = 16.sp,
                            lineHeight = 16.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier.fillMaxWidth().height(NUMBER_HEIGHT).padding(top = 1.dp, end = 3.dp),
                        )
                    }
                }
            }
        }
        if (cross > 0) {
            CrossTrack(groove, now, Modifier.fillMaxWidth().height(NUMBER_HEIGHT).align(Alignment.BottomCenter))
        }
        Ball(groove, now, gap, Modifier.matchParentSize())
    }
}

/**
 * The cross-rhythm's row: a block for each of its clicks, spread evenly across the bar, lit as
 * it sounds, and a cursor sweeping along at the cross-rhythm's own pace.
 */
@Composable
private fun CrossTrack(groove: Groove, now: State<Now?>, modifier: Modifier) {
    val vibe = LocalVibe.current
    val shadow = blockShadow()
    val count = groove.crossCount
    val color = vibe.stripes.getOrElse(1) { vibe.accent }
    Canvas(modifier.semantics { contentDescription = "$count against ${groove.beats}" }) {
        val gap = 4.dp.toPx()
        val w = (size.width - gap * (count - 1)) / count
        val h = 8.dp.toPx()
        val top = (size.height - h) / 2f + 1.dp.toPx()
        val n = now.value
        val crossTick = n?.cross?.takeIf { it.cross < count }
        val sounding = if (n != null && crossTick != null && n.sinceCrossMillis < 140f) crossTick.cross else -1
        for (k in 0 until count) {
            val left = k * (w + gap)
            drawRect(shadow, Offset(left + 2.dp.toPx(), top + 2.dp.toPx()), Size(w - 3.dp.toPx(), h))
            drawRect(
                if (k == sounding) vibe.accent else color.copy(alpha = 0.7f),
                Offset(left, top),
                Size(w - 3.dp.toPx(), h),
            )
            drawRect(vibe.ink.copy(alpha = if (k == sounding) 0.9f else 0.4f), Offset(left, top), Size(w - 3.dp.toPx(), h), style = Stroke(1.5.dp.toPx()))
        }
        if (n != null && crossTick != null) {
            // From the middle of the block just heard towards the middle of the next.
            val interval = 60_000f / n.beat.bpm * groove.beats / count
            val progress = (n.sinceCrossMillis / interval).coerceIn(0f, 1f)
            val position = (crossTick.cross + 0.5f + progress) % count
            val x = position * (w + gap) - 1.5.dp.toPx()
            val grid = 3.dp.toPx()
            val snapped = (x / grid).roundToInt() * grid
            drawRect(vibe.ink, Offset(snapped - 1.5.dp.toPx(), 0f), Size(3.dp.toPx(), size.height))
        }
    }
}

/** The tallest light, with its pips and number under it. */
private val LIGHTS_HEIGHT = 70.dp

/** The beat number under each light. */
private val NUMBER_HEIGHT = 18.dp

/** The part of [LIGHTS_HEIGHT] taken by the light itself, above its pips and number. */
private val LIGHT_TOP_ROOM = LIGHTS_HEIGHT - 5.dp - NUMBER_HEIGHT

private fun DrawScope.drawLight(
    accent: Accent,
    lit: Boolean,
    audible: Boolean,
    shadow: Color,
    accentColor: Color,
    primary: Color,
    ink: Color,
    background: Color,
) {
    val depth = 3.dp.toPx()
    val h = (size.height - depth) * accent.height()
    val top = size.height - depth - h
    val w = size.width - depth
    drawRect(shadow, Offset(depth, top + depth), Size(w, h))
    val fill = when {
        !lit -> background.copy(alpha = 0.55f)
        !audible || accent == Accent.MUTE -> ink.copy(alpha = 0.35f)
        accent == Accent.STRONG -> accentColor
        else -> primary
    }
    drawRect(fill, Offset(0f, top), Size(w, h))
    drawRect(if (lit) ink.copy(alpha = 0.9f) else ink.copy(alpha = 0.35f), Offset(0f, top), Size(w, h), style = Stroke(2.dp.toPx()))
}

/**
 * The hits judged against beat [beat] of this bar and the last: a pip for each along the top
 * of the light, left of centre for early and right for late (the light's half-width is 50 ms),
 * last bar's dimmer. The newest hit also outlines the light in its grade's colour for a moment.
 */
private fun DrawScope.drawHitPips(hits: List<Judgment>, beat: Int, bar: Int, accent: Accent, dark: Boolean) {
    val depth = 3.dp.toPx()
    val h = (size.height - depth) * accent.height()
    val top = size.height - depth - h
    val w = size.width - depth
    val pip = 4.dp.toPx()
    val newest = hits.lastOrNull()
    hits.forEach { hit ->
        val tick = hit.tick
        if (tick.isCross || tick.beat != beat || tick.step != 0) return@forEach
        val alpha = when (tick.bar) {
            bar -> 1f
            bar - 1 -> 0.35f
            else -> return@forEach
        }
        val x = w / 2f + (hit.offsetMillis / STRIP_MILLIS).coerceIn(-1f, 1f) * (w / 2f - pip)
        val color = gradeColor(hit.grade, dark).copy(alpha = alpha)
        drawRect(Color.Black.copy(alpha = 0.6f * alpha), Offset(x - pip / 2f + 1.dp.toPx(), top + 3.dp.toPx()), Size(pip, pip))
        drawRect(color, Offset(x - pip / 2f, top + 2.dp.toPx()), Size(pip, pip))
        if (hit === newest && abs(System.nanoTime() - hit.nanos) < 300_000_000L) {
            drawRect(color, Offset(0f, top), Size(w, h), style = Stroke(3.dp.toPx()))
        }
    }
}

/**
 * The bouncing ball. It sits on the light of the beat just heard and, as the next beat
 * approaches, arcs over to land on that light exactly as it sounds, so a player can see the
 * beat coming rather than only see it go. It moves on a chunky pixel grid, as a cabinet would.
 */
@Composable
private fun Ball(groove: Groove, now: State<Now?>, gap: Dp, modifier: Modifier) {
    val vibe = LocalVibe.current
    val shadow = blockShadow()
    Canvas(modifier) {
        val beats = groove.beats
        val n = now.value
        if (n == null) {
            // At rest on beat one, waiting for START (or for the first click to be heard).
            drawBall(centreOf(0, beats, gap), landingY(groove, 0), 0f, vibe.ink, shadow)
            return@Canvas
        }
        val from = n.beat.beat % beats
        val to = (from + 1) % beats
        val p = n.beatProgress
        val x = centreOf(from, beats, gap) + (centreOf(to, beats, gap) - centreOf(from, beats, gap)) * p
        val y0 = landingY(groove, from)
        val y1 = landingY(groove, to)
        // A longer hop (back to beat one) goes higher.
        val reach = if (to < from) 1.5f else 1f
        val arc = (size.height - LIGHTS_HEIGHT.toPx()) * 0.9f * reach + 6.dp.toPx()
        val y = y0 + (y1 - y0) * p - arc * 4f * p * (1f - p)
        // A squash on landing.
        val squash = if (p < 0.08f) 1f - p / 0.08f else 0f
        drawBall(x, y, squash, if (n.beat.audible) vibe.ink else vibe.ink.copy(alpha = 0.4f), shadow)
    }
}

private fun DrawScope.centreOf(beat: Int, beats: Int, gap: Dp): Float {
    val g = gap.toPx()
    val w = (size.width - (beats - 1) * g) / beats
    return beat * (w + g) + (w - 3.dp.toPx()) / 2f
}

/** Where the ball's bottom rests on the light of [beat]. */
private fun DrawScope.landingY(groove: Groove, beat: Int): Float {
    val lightsTop = size.height - LIGHTS_HEIGHT.toPx()
    val room = LIGHT_TOP_ROOM.toPx() - 3.dp.toPx()
    return lightsTop + room * (1f - groove.accent(beat).height())
}

private fun DrawScope.drawBall(x: Float, bottom: Float, squash: Float, color: Color, shadow: Color) {
    val grid = 3.dp.toPx()
    val side = 12.dp.toPx()
    val w = side * (1f + 0.35f * squash)
    val h = side * (1f - 0.35f * squash)
    val left = ((x - w / 2f) / grid).roundToInt() * grid
    val top = ((bottom - h - 2.dp.toPx()) / grid).roundToInt() * grid
    drawRect(shadow, Offset(left + grid, top + grid), Size(w, h))
    drawRect(color, Offset(left, top), Size(w, h))
}
