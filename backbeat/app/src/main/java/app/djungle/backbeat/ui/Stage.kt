package app.djungle.backbeat.ui

import android.util.Log
import android.view.KeyEvent
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.djungle.backbeat.engine.Accent
import app.djungle.backbeat.engine.Groove
import app.djungle.backbeat.engine.Now
import app.djungle.backbeat.engine.tempoMarking
import app.djungle.backbeat.ui.theme.Arcade
import app.djungle.backbeat.ui.theme.LocalVibe
import app.djungle.backbeat.ui.theme.Vcr

/** What a key on a keyboard, a page-turner pedal or a headset's button asks for. */
enum class Command { START_STOP, NEXT, PREVIOUS, FASTER, SLOWER, TAP }

/**
 * Keys from anything plugged in or paired: a Bluetooth page-turner pedal (which sends page up
 * and down, or the arrows), a headset's button, or a keyboard. While a tape's label is being
 * written ([typing]) the keys that type are left alone.
 */
object Pedal {
    /** Set while a text field has the keyboard, so its keys are not taken. */
    @Volatile var typing = false

    /** Whoever is on the screen to act on a command; true if it did. */
    @Volatile var handler: ((Command) -> Boolean)? = null

    fun command(keyCode: Int, typing: Boolean): Command? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP,
        -> Command.START_STOP
        KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT -> Command.NEXT
        KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> Command.PREVIOUS
        else -> if (typing) {
            null
        } else {
            when (keyCode) {
                KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Command.START_STOP
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> Command.NEXT
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> Command.PREVIOUS
                KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD -> Command.FASTER
                KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> Command.SLOWER
                KeyEvent.KEYCODE_T -> Command.TAP
                else -> null
            }
        }
    }

    /** Handles a key from the activity; true if it was Backbeat's. */
    fun dispatch(event: KeyEvent): Boolean {
        val command = command(event.keyCode, typing) ?: return false
        val handle = handler ?: return false
        // Only the press acts (held, the tempo keys repeat); its release is swallowed with it.
        if (event.action == KeyEvent.ACTION_DOWN && (event.repeatCount == 0 || command == Command.FASTER || command == Command.SLOWER)) {
            Log.i("Pedal", "${KeyEvent.keyCodeToString(event.keyCode)}: $command")
            return handle(command)
        }
        return true
    }
}

/**
 * The big screen, for a drummer to read from the stool or a band from across the room: the beat
 * being played as a number filling the screen, lit on the one and flashing with every beat,
 * the tempo under it, and the tape that is loaded and the one after it, with big keys to
 * step between them.
 */
@Composable
fun StageView(
    groove: Groove,
    playing: Boolean,
    liveBpm: Int?,
    now: State<Now?>,
    tapeName: String?,
    nextName: String?,
    onStartStop: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val vibe = LocalVibe.current
    val beat by remember { derivedStateOf { now.value?.beat } }
    val bpm = if (playing) liveBpm ?: groove.bpm else groove.bpm
    Box(
        Modifier
            .fillMaxSize()
            .background(vibe.background)
            .clickable(remember { MutableInteractionSource() }, indication = null) { }
            .drawBehind {
                // A flash with every beat heard, brighter on the one.
                val n = now.value ?: return@drawBehind
                if (!n.beat.audible || n.beat.accent == Accent.MUTE) return@drawBehind
                val strength = if (n.beat.isDownbeat) 0.6f else 0.25f
                val fade = (1f - n.sinceBeatMillis / 180f).coerceIn(0f, 1f)
                if (fade > 0f) drawRect((if (n.beat.isDownbeat) vibe.accent else vibe.primary).copy(alpha = strength * fade))
            }
            .scanlines(Color.Black.copy(alpha = if (vibe.isDark) 0.2f else 0.05f)),
    ) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                DeckMark(playing, color = osdColor())
                Text(
                    "  " + (tapeName ?: "").uppercase(),
                    style = osdStyle(26.sp, osdColor()),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                RetroChip("x", selected = false, onClick = onClose, modifier = Modifier.semantics { contentDescription = "leave the big screen" })
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val b = beat
                val text = if (b == null) "-" else (b.beat + 1).toString()
                val size = bigSize(maxWidth, maxHeight, text.length)
                val color = when {
                    b == null -> vibe.ink.copy(alpha = 0.4f)
                    !b.audible || b.accent == Accent.MUTE -> vibe.ink.copy(alpha = 0.3f)
                    b.isDownbeat -> vibe.accent
                    else -> vibe.ink
                }
                Box(contentAlignment = Alignment.Center) {
                    Text(text, style = TextStyle(fontFamily = Arcade, fontSize = size, color = blockShadow()), modifier = Modifier.padding(start = 8.dp, top = 8.dp))
                    Text(text, style = TextStyle(fontFamily = Arcade, fontSize = size, color = color))
                }
            }
            Text(
                "$bpm BPM · ${groove.meter.label} · ${tempoMarking(bpm).uppercase()}",
                style = osdStyle(30.sp, osdColor()),
                maxLines = 1,
            )
            StageLights(groove, now, Modifier.fillMaxWidth().height(36.dp).padding(top = 10.dp))
            if (nextName != null) {
                Text("NEXT: ${nextName.uppercase()}", style = osdStyle(22.sp, osdColor().copy(alpha = 0.75f)), maxLines = 1, modifier = Modifier.padding(top = 8.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkipKey(forward = false, onClick = onPrevious)
                TransportButton(playing, onClick = onStartStop, modifier = Modifier.weight(1f))
                SkipKey(forward = true, onClick = onNext)
            }
        }
    }
}

/** As large as the room allows: the beat number takes most of the height, or the width. */
@Composable
private fun bigSize(width: Dp, height: Dp, digits: Int) = with(LocalDensity.current) {
    // Press Start's figures are square, and a little shorter than the type size.
    minOf(height.toPx() * 0.8f, width.toPx() * 0.85f / digits.coerceAtLeast(1)).toSp()
}

/** One block for each beat, the one being heard lit. */
@Composable
private fun StageLights(groove: Groove, now: State<Now?>, modifier: Modifier) {
    val vibe = LocalVibe.current
    val shadow = blockShadow()
    Canvas(modifier) {
        val beats = groove.beats
        val gap = 6.dp.toPx()
        val w = (size.width - gap * (beats - 1)) / beats
        val heard = now.value?.beat
        for (i in 0 until beats) {
            val left = i * (w + gap)
            val accent = groove.accent(i)
            val lit = heard != null && heard.beat % beats == i
            drawRect(shadow, Offset(left + 3.dp.toPx(), 3.dp.toPx()), Size(w - 3.dp.toPx(), size.height - 3.dp.toPx()))
            drawRect(
                when {
                    !lit -> vibe.soft
                    accent == Accent.STRONG -> vibe.accent
                    else -> vibe.primary
                },
                Offset(left, 0f),
                Size(w - 3.dp.toPx(), size.height - 3.dp.toPx()),
            )
        }
    }
}

/** ◀◀ or ▶▶: the tape before or after, as on a tape deck. */
@Composable
private fun SkipKey(forward: Boolean, onClick: () -> Unit) {
    val vibe = LocalVibe.current
    RetroBlock(
        Modifier.semantics { contentDescription = if (forward) "next tape" else "previous tape" },
        fill = vibe.surface,
        onClick = onClick,
        depth = 6.dp,
        corner = 6.dp,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
    ) {
        Canvas(Modifier.size(width = 26.dp, height = 16.dp)) {
            val half = size.width / 2f
            for (k in 0 until 2) {
                val left = k * half
                val path = Path().apply {
                    if (forward) {
                        moveTo(left, 0f)
                        lineTo(left + half, size.height / 2f)
                        lineTo(left, size.height)
                    } else {
                        moveTo(left + half, 0f)
                        lineTo(left, size.height / 2f)
                        lineTo(left + half, size.height)
                    }
                    close()
                }
                drawPath(path, vibe.ink)
            }
        }
    }
}

/** The key beside START that opens the big screen: a little television. */
@Composable
fun StageKey(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val vibe = LocalVibe.current
    RetroBlock(
        modifier.semantics { contentDescription = "big screen" },
        fill = vibe.surface,
        onClick = onClick,
        depth = 6.dp,
        corner = 6.dp,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 15.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(width = 28.dp, height = 20.dp)) {
                val stroke = 2.5.dp.toPx()
                drawRect(vibe.ink, Offset.Zero, Size(size.width, size.height - 4.dp.toPx()))
                drawRect(vibe.soft, Offset(stroke, stroke), Size(size.width - 2 * stroke, size.height - 4.dp.toPx() - 2 * stroke))
                drawRect(vibe.accent, Offset(size.width * 0.3f, size.height * 0.25f), Size(size.width * 0.4f, size.height * 0.3f))
                drawRect(vibe.ink, Offset(size.width * 0.3f, size.height - 3.dp.toPx()), Size(size.width * 0.4f, 3.dp.toPx()))
            }
            Text("BIG", color = vibe.ink, fontFamily = Vcr, fontSize = 16.sp, lineHeight = 16.sp)
        }
    }
}
