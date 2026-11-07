package app.djungle.backbeat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.djungle.backbeat.ui.theme.Arcade
import app.djungle.backbeat.ui.theme.LocalVibe
import app.djungle.backbeat.ui.theme.Vcr
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The colour of the hard drop shadow under every block: dark on any vibe. */
@Composable
fun blockShadow(): Color {
    val vibe = LocalVibe.current
    return if (vibe.isDark) Color.Black else vibe.ink.copy(alpha = 0.85f)
}

/**
 * The basic building block: a nearly square slab with a hard, unblurred drop shadow, like an
 * arcade button. Pressing it (or [held]) sinks it into its shadow.
 */
@Composable
fun RetroBlock(
    modifier: Modifier = Modifier,
    fill: Color = LocalVibe.current.surface,
    border: Color = blockShadow(),
    onClick: (() -> Unit)? = null,
    held: Boolean = false,
    depth: Dp = 4.dp,
    corner: Dp = 4.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val sunk = held || pressed
    val shape = RoundedCornerShape(corner)
    val shadow = blockShadow()
    Box(
        modifier
            .padding(end = depth, bottom = depth)
            .drawBehind {
                drawRoundRect(shadow, Offset(depth.toPx(), depth.toPx()), size, CornerRadius(corner.toPx()))
            }
            .offset { if (sunk) IntOffset(depth.roundToPx(), depth.roundToPx()) else IntOffset.Zero }
            .clip(shape)
            .background(fill)
            .border(2.dp, border, shape)
            .then(if (onClick != null) Modifier.clickable(source, indication = null, onClick = onClick) else Modifier)
            .padding(contentPadding),
        contentAlignment = contentAlignment,
        content = content,
    )
}

/** A heading over each group of settings, like a line of a VCR's set-up menu. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    val vibe = LocalVibe.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(vibe.accent))
        Text(
            text.uppercase(),
            color = vibe.ink.copy(alpha = 0.85f),
            fontFamily = Vcr,
            fontSize = 22.sp,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * A tappable label. The chosen one is lit in the vibe's colour and pressed in. Labels in the
 * app's own lettering are shouted in capitals; caption-font samples keep their own case.
 */
@Composable
fun RetroChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fontFamily: FontFamily? = null,
    fontSize: TextUnit = 20.sp,
    padding: Dp = 14.dp,
) {
    val vibe = LocalVibe.current
    RetroBlock(
        modifier,
        fill = if (selected) vibe.primary else vibe.surface,
        onClick = onClick,
        held = selected,
        depth = 3.dp,
        contentPadding = PaddingValues(horizontal = padding, vertical = 6.dp),
    ) {
        Text(
            if (fontFamily == null) text.uppercase() else text,
            color = if (selected) vibe.onPrimary else vibe.ink,
            fontFamily = fontFamily ?: Vcr,
            fontSize = if (fontFamily == null) fontSize else 16.sp,
            maxLines = 1,
        )
    }
}

@Composable
fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(options) { option ->
            RetroChip(label(option), option == selected, onClick = { onSelect(option) })
        }
    }
}

/** A setting that is on or off, with a chunky ON/OFF key instead of a slider switch. */
@Composable
fun ToggleRow(label: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val vibe = LocalVibe.current
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label.uppercase(), color = vibe.ink, fontFamily = Vcr, fontSize = 22.sp)
            Text(hint, color = vibe.ink.copy(alpha = 0.6f), fontFamily = Vcr, fontSize = 18.sp)
        }
        RetroBlock(
            Modifier.width(76.dp),
            fill = if (checked) vibe.primary else vibe.surface,
            held = checked,
            depth = 3.dp,
            contentPadding = PaddingValues(vertical = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(if (checked) vibe.accent else vibe.ink.copy(alpha = 0.25f)))
                Text(
                    if (checked) "ON" else "OFF",
                    color = if (checked) vibe.onPrimary else vibe.ink.copy(alpha = 0.7f),
                    fontFamily = Vcr,
                    fontSize = 20.sp,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

/**
 * A row of blocks that light up from the left, like the volume bar a television shows.
 * Tap or slide along it. [steps] blocks stand for [min]..1.
 */
@Composable
fun SegmentBar(value: Float, min: Float, steps: Int, label: String, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val vibe = LocalVibe.current
    // The gesture handlers outlive a recomposition, so they call whatever onChange is newest.
    val change by rememberUpdatedState(onChange)
    val lit = (((value - min) / (1f - min)) * (steps - 1)).roundToInt() + 1
    fun valueAt(x: Float, width: Int): Float {
        val index = ((x / width.coerceAtLeast(1)) * steps).toInt().coerceIn(0, steps - 1)
        return min + (1f - min) * index / (steps - 1)
    }
    Row(
        modifier
            .height(30.dp)
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(value, min..1f, steps - 2)
                setProgress { target ->
                    change(target.coerceIn(min, 1f))
                    true
                }
            }
            .pointerInput(steps, min) {
                detectTapGestures { change(valueAt(it.x, size.width)) }
            }
            .pointerInput(steps, min) {
                detectHorizontalDragGestures { pointer, _ -> change(valueAt(pointer.position.x, size.width)) }
            },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(steps) { i ->
            val on = i < lit
            // The blocks grow a little taller towards the right, as volume bars do.
            val rise = 0.45f + 0.55f * i / (steps - 1)
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(rise)
                        .background(if (on) (if (i == lit - 1) vibe.accent else vibe.primary) else vibe.soft)
                )
            }
        }
    }
}

/** The big call to action: an arcade start button with cabinet stripes along its foot. */
@Composable
fun BigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val vibe = LocalVibe.current
    RetroBlock(
        modifier,
        fill = vibe.primary,
        onClick = onClick,
        depth = 6.dp,
        corner = 6.dp,
    ) {
        // As wide as the lettering, so the stripes run the full width of the button and no further.
        Column(Modifier.width(IntrinsicSize.Max), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text.uppercase(),
                color = vibe.onPrimary,
                fontFamily = Arcade,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(start = 26.dp, end = 26.dp, top = 16.dp, bottom = 12.dp),
            )
            ArcadeStripes(vibe.stripes, Modifier.fillMaxWidth(), band = 3.dp, gap = 0.dp)
        }
    }
}

/** A short message that slides up from the bottom, in on-screen-display lettering. */
@Composable
fun ToastPill(text: String, visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
        modifier = modifier,
    ) {
        Text(
            text.uppercase(),
            style = osdStyle(22.sp),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .background(Color.Black.copy(alpha = 0.8f))
                .border(2.dp, Color.White.copy(alpha = 0.8f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/**
 * A key that acts once when tapped and, held down, again and again and faster, like the
 * tuning buttons on an old radio. It waits for a tap or a hold rather than acting the moment
 * a finger lands, so a scroll that happens to start on it changes nothing.
 */
@Composable
fun RepeatKey(
    text: String,
    label: String,
    onStep: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 16.sp,
) {
    val vibe = LocalVibe.current
    val step by rememberUpdatedState(onStep)
    val scope = rememberCoroutineScope()
    var held by remember { mutableStateOf(false) }
    RetroBlock(
        modifier
            .semantics {
                contentDescription = label
                role = Role.Button
                onClick {
                    step()
                    true
                }
            }
            .pointerInput(Unit) {
                // Whether this press has already stepped by being held, so its release adds no more.
                var repeated = false
                detectTapGestures(
                    onPress = {
                        held = true
                        repeated = false
                        val repeating = scope.launch {
                            delay(420)
                            var wait = 110L
                            while (true) {
                                repeated = true
                                step()
                                delay(wait)
                                wait = (wait * 0.88).toLong().coerceAtLeast(30L)
                            }
                        }
                        // False if the press turned into a scroll: then nothing happens.
                        tryAwaitRelease()
                        repeating.cancel()
                        held = false
                    },
                    onTap = { if (!repeated) step() },
                )
            },
        held = held,
        depth = 4.dp,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(text, color = vibe.ink, fontFamily = Arcade, fontSize = fontSize)
    }
}

/** A number set with - and + keys either side of it, like the timer menu of a VCR. */
@Composable
fun StepperRow(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    format: (Int) -> String = { it.toString() },
) {
    val vibe = LocalVibe.current
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label.uppercase(), color = vibe.ink, fontFamily = Vcr, fontSize = 22.sp, modifier = Modifier.weight(1f))
        RepeatKey("-", "less $label", onStep = { change((current - 1).coerceIn(range)) }, fontSize = 13.sp)
        Text(
            format(value).uppercase(),
            color = vibe.accent,
            fontFamily = Vcr,
            fontSize = 24.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.width(104.dp),
        )
        RepeatKey("+", "more $label", onStep = { change((current + 1).coerceIn(range)) }, fontSize = 13.sp)
    }
}
