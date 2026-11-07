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
import androidx.compose.foundation.layout.size
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
import app.djungle.backbeat.engine.Groove
import app.djungle.backbeat.engine.Now
import app.djungle.backbeat.engine.Practice
import app.djungle.backbeat.engine.tempoMarking
import app.djungle.backbeat.ui.theme.Arcade
import app.djungle.backbeat.ui.theme.LocalVibe
import app.djungle.backbeat.ui.theme.Vcr
import kotlin.math.roundToInt

/** How tall a beat light stands for each accent, as a share of the tallest. */
private fun Accent.height(): Float = when (this) {
    Accent.STRONG -> 1f
    Accent.NORMAL -> 0.66f
    Accent.SOFT -> 0.4f
    Accent.MUTE -> 0.16f
}

/**
 * The television: the tempo in big marquee digits, the time signature, bar and tape counter
 * in VCR lettering, and a row of beat lights with a pixel ball bouncing along them.
 *
 * [fold] runs from 0 (the full picture) to 1 (folded into a strip, to make room for the
 * settings): the digits fade away, the tempo moves up into the top line, and the lights
 * crouch a little lower.
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

    RetroBlock(modifier.fillMaxWidth(), contentPadding = PaddingValues(10.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(18.dp))
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
                    Text(
                        if (folded) {
                            "$bpm BPM  ${groove.meter.label}"
                        } else {
                            "${groove.meter.label}  ${groove.subdivision.label.uppercase()}"
                        },
                        style = osdStyle(20.sp),
                        maxLines = 1,
                    )
                    Box(Modifier.weight(1f))
                    val counter = bar
                    if (playing) {
                        val barText = "BAR " + ((counter ?: 0) + 1).toString().padStart(3, '0')
                        Text(if (folded) barText else "$barText  ${tapeCounter(counterSeconds)}", style = osdStyle(20.sp), maxLines = 1)
                    } else {
                        Text("PRESS START", style = osdStyle(20.sp, if (blink) Color.White else Color.Transparent), maxLines = 1)
                    }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds(), contentAlignment = Alignment.Center) {
                    val room = maxWidth
                    val tall = maxHeight - STATUS_HEIGHT
                    if (digitsAlpha > 0f) Column(Modifier.graphicsLayer { alpha = digitsAlpha }, horizontalAlignment = Alignment.CenterHorizontally) {
                        BigTempo(bpm, room, tall)
                        val status = when {
                            playing && silent -> if (blink) "· SILENT BAR ·" else ""
                            playing && practice.ramp && bpm != practice.rampTarget -> "BPM · RAMP TO ${practice.rampTarget}"
                            else -> "BPM · " + tempoMarking(bpm).uppercase()
                        }
                        Text(status, style = osdStyle(20.sp), maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                BeatLights(groove, now, onAccent, lightsBox)
            }
            Box(Modifier.matchParentSize().scanlines(Color.Black.copy(alpha = if (vibe.isDark) 0.16f else 0.05f)))
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
private fun tapeCounter(seconds: Long): String =
    if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)

/** The height of the picture folded into a strip: one line of text over the lights. */
val TUBE_FOLDED = 124.dp

/** The box the lights and their ball sit in, unfolded and folded. */
private val LIGHTS_BOX = 96.dp
private val LIGHTS_BOX_FOLDED = 84.dp

/** The tempo in marquee digits, as big as [width] by [height] allows. */
@Composable
private fun BigTempo(bpm: Int, width: Dp, height: Dp) {
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
    StripedText(bpm.toString(), style.copy(fontSize = 56.sp * scale), vibe.stripes, blockShadow())
}

/**
 * One light per beat. Its height shows the beat's accent; tapping it steps to the next. The
 * light for the beat being heard is lit, small pips beneath it count the clicks between beats,
 * and a pixel ball hops from light to light, landing on each just as its beat sounds.
 */
@Composable
private fun BeatLights(groove: Groove, now: State<Now?>, onAccent: (Int) -> Unit, boxHeight: Dp) {
    val vibe = LocalVibe.current
    val shadow = blockShadow()
    val beats = groove.beats
    val gap = if (beats > 8) 3.dp else 6.dp
    val steps = groove.subdivision.steps
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
        Ball(groove, now, gap, Modifier.matchParentSize())
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
