package app.djungle.backbeat.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.djungle.backbeat.engine.MAX_BPM
import app.djungle.backbeat.engine.MIN_BPM
import app.djungle.backbeat.ui.theme.Arcade
import app.djungle.backbeat.ui.theme.LocalVibe
import app.djungle.backbeat.ui.theme.Vcr
import app.djungle.backbeat.ui.theme.Vibe
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Lettering for the on-screen display: white on dark vibes, the vibe's ink on light ones. */
@Composable
fun osdColor(): Color = LocalVibe.current.let { if (it.isDark) Color.White else it.ink }

/**
 * One slim line: the deck's PLAY or STOP, the marquee logo, and the channel, over the cabinet
 * stripes. It used to be three rows deep; the room went to the controls.
 */
@Composable
fun Header(playing: Boolean, onLogoTap: () -> Unit) {
    val vibe = LocalVibe.current
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DeckMark(playing, color = osdColor())
            val measurer = rememberTextMeasurer()
            BoxWithConstraints(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                val logo = TextStyle(fontFamily = Arcade, fontSize = 18.sp)
                val natural = remember(measurer) { measurer.measure(LOGO, logo, softWrap = false).size.width }
                val room = constraints.maxWidth * 0.95f
                val scale = if (natural > room) room / natural else 1f
                StripedText(
                    LOGO,
                    logo.copy(fontSize = 18.sp * scale),
                    vibe.stripes,
                    blockShadow(),
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onLogoTap,
                    ),
                )
            }
            Text(vibe.channelName, style = osdStyle(color = osdColor()))
        }
        ArcadeStripes(vibe.stripes, Modifier.fillMaxWidth().padding(top = 6.dp), band = 2.dp, gap = 1.dp)
    }
}

private const val LOGO = "BACKBEAT"

/**
 * The tempo dial: a ruler of BPM that slides under a fixed needle, like the tuning scale of
 * an old radio. Drag it left for faster and right for slower, one BPM at a time.
 */
@Composable
fun TempoDial(bpm: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val vibe = LocalVibe.current
    val change by rememberUpdatedState(onChange)
    val current by rememberUpdatedState(bpm)
    var fine by remember { mutableFloatStateOf(bpm.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(bpm) { if (!dragging) fine = bpm.toFloat() }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontFamily = Vcr, fontSize = 18.sp, color = vibe.ink.copy(alpha = 0.85f))

    RetroBlock(modifier.height(62.dp), fill = vibe.background, depth = 4.dp) {
        Canvas(
            Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = "tempo"
                    stateDescription = "$bpm beats a minute"
                    progressBarRangeInfo = ProgressBarRangeInfo(bpm.toFloat(), MIN_BPM.toFloat()..MAX_BPM.toFloat(), MAX_BPM - MIN_BPM - 1)
                    setProgress { target ->
                        change(target.roundToInt().coerceIn(MIN_BPM, MAX_BPM))
                        true
                    }
                }
                .pointerInput(Unit) {
                    val spacing = TICK_SPACING.toPx()
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragging = true
                            fine = current.toFloat()
                        },
                        onDragEnd = {
                            dragging = false
                            fine = current.toFloat()
                        },
                        onDragCancel = {
                            dragging = false
                            fine = current.toFloat()
                        },
                    ) { pointer, dx ->
                        pointer.consume()
                        fine = (fine - dx / spacing).coerceIn(MIN_BPM.toFloat(), MAX_BPM.toFloat())
                        val whole = fine.roundToInt()
                        if (whole != current) change(whole)
                    }
                },
        ) {
            val px = TICK_SPACING.toPx()
            val middle = size.width / 2f
            val shown = if (dragging) fine else bpm.toFloat()
            val first = floor(shown - middle / px).toInt() - 1
            val last = ceil(shown + middle / px).toInt() + 1
            val tickWidth = 2.dp.toPx()
            // The numbers run along the top, clear of the needle, which only crosses the ticks.
            val scaleTop = LABEL_ROOM.toPx()
            for (value in first..last) {
                if (value < MIN_BPM || value > MAX_BPM) continue
                val x = middle + (value - shown) * px
                val share = when {
                    value % 10 == 0 -> 0.42f
                    value % 5 == 0 -> 0.28f
                    else -> 0.15f
                }
                val h = (size.height - scaleTop) * share * 1.7f
                drawRect(vibe.ink.copy(alpha = 0.7f), Offset(x - tickWidth / 2f, size.height - h - 3.dp.toPx()), Size(tickWidth, h))
                if (value % 10 == 0) {
                    // The number nearest the needle is lit.
                    val near = kotlin.math.abs(value - shown) < 5f
                    val text = measurer.measure(value.toString(), if (near) labelStyle.copy(color = vibe.accent) else labelStyle)
                    drawText(text, topLeft = Offset(x - text.size.width / 2f, 1.dp.toPx()))
                }
            }
            // The needle: a pointer at the top of the scale and a line down through it.
            val needle = Path().apply {
                moveTo(middle - 6.dp.toPx(), scaleTop)
                lineTo(middle + 6.dp.toPx(), scaleTop)
                lineTo(middle, scaleTop + 8.dp.toPx())
                close()
            }
            drawRect(vibe.accent, Offset(middle - 1.5.dp.toPx(), scaleTop), Size(3.dp.toPx(), size.height - scaleTop))
            drawPath(needle, vibe.accent)
            // The scale fades out at each end, as if curving away behind the glass.
            val fade = size.width * 0.18f
            drawRect(
                Brush.horizontalGradient(listOf(vibe.background, Color.Transparent), startX = 0f, endX = fade),
                Offset.Zero,
                Size(fade, size.height),
            )
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, vibe.background), startX = size.width - fade, endX = size.width),
                Offset(size.width - fade, 0f),
                Size(fade, size.height),
            )
            drawScanlines(Color.Black.copy(alpha = if (vibe.isDark) 0.18f else 0.04f))
        }
    }
}

/** The tempo controls in one row: slower, the dial, faster, and TAP. */
@Composable
fun TempoRow(bpm: Int, onBpm: (Int) -> Unit, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val current by rememberUpdatedState(bpm)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RepeatKey("-", "slower", onStep = { onBpm(current - 1) })
        TempoDial(bpm, onChange = onBpm, modifier = Modifier.weight(1f))
        RepeatKey("+", "faster", onStep = { onBpm(current + 1) })
        TapKey(onTap = onTap)
    }
}

/** One BPM's width on the dial. */
private val TICK_SPACING = 9.dp

/** The strip along the top of the dial that holds its numbers. */
private val LABEL_ROOM = 21.dp

/**
 * TAP: tap it along with the music and the tempo follows. It reacts the moment a finger
 * lands, not when it lifts, so the taps are timed as played.
 */
@Composable
fun TapKey(onTap: () -> Unit, modifier: Modifier = Modifier) {
    val vibe = LocalVibe.current
    val tap by rememberUpdatedState(onTap)
    var held by remember { mutableStateOf(false) }
    RetroBlock(
        modifier
            .semantics {
                contentDescription = "tap tempo"
                role = Role.Button
                onClick {
                    tap()
                    true
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        held = true
                        tap()
                        tryAwaitRelease()
                        held = false
                    },
                )
            },
        fill = if (held) vibe.accent else vibe.surface,
        held = held,
        depth = 4.dp,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text("TAP", color = if (held) vibe.onPrimary else vibe.ink, fontFamily = Arcade, fontSize = 13.sp)
    }
}

/** START and STOP: the big arcade button, full width, with the cabinet stripes along its foot. */
@Composable
fun TransportButton(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val vibe = LocalVibe.current
    RetroBlock(
        modifier.fillMaxWidth().semantics { stateDescription = if (playing) "playing" else "stopped" },
        fill = if (playing) vibe.accent else vibe.primary,
        onClick = onClick,
        depth = 6.dp,
        corner = 6.dp,
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                Modifier.padding(top = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                // The symbol shows what pressing it will do.
                DeckSymbol(!playing, vibe.onPrimary, 14.dp)
                Box(Modifier.size(12.dp))
                Text(if (playing) "STOP" else "START", color = vibe.onPrimary, fontFamily = Arcade, fontSize = 15.sp)
            }
            ArcadeStripes(vibe.stripes, Modifier.fillMaxWidth(), band = 3.dp, gap = 0.dp)
        }
    }
}

/** The vibes, as channels on a television. */
@Composable
fun ChannelPicker(current: Vibe, onChange: (Vibe) -> Unit, modifier: Modifier = Modifier) {
    RetroBlock(modifier.fillMaxWidth(), fill = current.surface, contentPadding = PaddingValues(12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(current.channelName, color = current.accent, fontFamily = Vcr, fontSize = 24.sp)
                Text(
                    " · ${current.label.uppercase()}",
                    color = current.ink,
                    fontFamily = Vcr,
                    fontSize = 24.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Vibe.entries.forEach { vibe ->
                    RetroBlock(
                        Modifier
                            .weight(1f)
                            .aspectRatio(0.9f)
                            .semantics { contentDescription = "vibe ${vibe.label}" },
                        fill = vibe.background,
                        border = if (vibe == current) current.accent else blockShadow(),
                        onClick = { onChange(vibe) },
                        held = vibe == current,
                        depth = 3.dp,
                        contentPadding = PaddingValues(5.dp),
                    ) {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                            Text(vibe.channel.toString(), color = vibe.ink, fontFamily = Arcade, fontSize = 11.sp)
                            ArcadeStripes(vibe.stripes, Modifier.fillMaxWidth(), band = 3.dp, gap = 1.dp)
                        }
                    }
                }
            }
        }
    }
}

/** A small red square that blinks, like the record light on a camcorder. */
@Composable
fun RecLight(on: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.size(10.dp)) { if (on) drawRect(Color(0xFFE5483F)) }
}
