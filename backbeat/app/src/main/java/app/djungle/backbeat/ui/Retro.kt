package app.djungle.backbeat.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.djungle.backbeat.ui.theme.LocalVibe
import app.djungle.backbeat.ui.theme.Vcr
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Fine monochrome noise, tiled over the screen like the grain of an old tape. */
private val grainTile: ImageBitmap by lazy {
    val side = 128
    val random = Random(23)
    val pixels = IntArray(side * side) {
        val v = if (random.nextBoolean()) 255 else 0
        android.graphics.Color.argb(random.nextInt(0, 26), v, v, v)
    }
    Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Thin horizontal lines, like the rows of a cathode-ray tube. */
fun DrawScope.drawScanlines(color: Color, spacing: Dp = 3.dp, thickness: Dp = 1.dp) {
    val step = spacing.toPx().coerceAtLeast(2f)
    val line = thickness.toPx().coerceAtLeast(1f)
    var y = 0f
    while (y < size.height) {
        drawRect(color, Offset(0f, y), Size(size.width, line))
        y += step
    }
}

fun Modifier.scanlines(color: Color, spacing: Dp = 3.dp) = drawBehind { drawScanlines(color, spacing) }

/**
 * The backdrop behind every screen: the vibe's colour seen through a television, with
 * scanlines, tape grain, darkened corners and a slow tracking band rolling down the picture.
 */
@Composable
fun RetroBackdrop(modifier: Modifier = Modifier) {
    val vibe = LocalVibe.current
    val roll by rememberInfiniteTransition(label = "tracking").animateFloat(
        initialValue = -0.25f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(11_000, easing = LinearEasing)),
        label = "band",
    )
    val grain = remember { ShaderBrush(ImageShader(grainTile, TileMode.Repeated, TileMode.Repeated)) }
    val line = if (vibe.isDark) Color.Black.copy(alpha = 0.32f) else vibe.ink.copy(alpha = 0.05f)
    val corners = if (vibe.isDark) Color.Black.copy(alpha = 0.55f) else vibe.ink.copy(alpha = 0.16f)
    Canvas(modifier) {
        drawRect(vibe.background)
        drawRect(grain)
        drawScanlines(line)
        // The tracking band: a soft lighter stripe with a couple of sharp glitch lines in it.
        val bandHeight = size.height * 0.07f
        val top = roll * size.height
        if (top < size.height && top + bandHeight > 0f) {
            drawRect(
                Brush.verticalGradient(
                    listOf(Color.Transparent, vibe.ink.copy(alpha = 0.06f), Color.Transparent),
                    startY = top,
                    endY = top + bandHeight,
                ),
                Offset(0f, top),
                Size(size.width, bandHeight),
            )
            drawRect(vibe.ink.copy(alpha = 0.07f), Offset(size.width * 0.12f, top + bandHeight * 0.4f), Size(size.width * 0.6f, 1.dp.toPx()))
            drawRect(vibe.ink.copy(alpha = 0.05f), Offset(size.width * 0.4f, top + bandHeight * 0.62f), Size(size.width * 0.55f, 1.dp.toPx()))
        }
        drawRect(
            Brush.radialGradient(
                0.5f to Color.Transparent,
                1f to corners,
                center = center,
                radius = size.maxDimension * 0.72f,
            )
        )
    }
}

/** Horizontal bands of colour, like the stripes down the side of an arcade cabinet. */
@Composable
fun ArcadeStripes(colors: List<Color>, modifier: Modifier = Modifier, band: Dp = 4.dp, gap: Dp = 2.dp) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        colors.forEach { color ->
            Box(Modifier.fillMaxWidth().height(band).drawBehind { drawRect(color) })
        }
    }
}

/** True and false by turns, for lettering that blinks like "INSERT COIN". */
@Composable
fun rememberBlink(periodMillis: Long = 600L): Boolean {
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(periodMillis) {
        while (true) {
            delay(periodMillis)
            tick++
        }
    }
    return tick % 2L == 0L
}

/** White on-screen lettering with the faint dark edge a VCR gave it so it read over anything. */
fun osdStyle(size: TextUnit = 22.sp, color: Color = Color.White) = TextStyle(
    fontFamily = Vcr,
    fontSize = size,
    color = color,
    letterSpacing = 1.sp,
    shadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(2f, 2f), blurRadius = 0f),
)

/** The symbol beside "PLAY" or "STOP". VT323 has no such glyphs, so they are drawn. */
@Composable
fun DeckSymbol(playing: Boolean, color: Color, side: Dp) {
    Canvas(Modifier.size(side)) {
        val path = Path().apply {
            if (playing) {
                moveTo(size.width * 0.12f, 0f)
                lineTo(size.width, size.height / 2f)
                lineTo(size.width * 0.12f, size.height)
            } else {
                val inset = size.width * 0.12f
                moveTo(inset, inset)
                lineTo(size.width - inset, inset)
                lineTo(size.width - inset, size.height - inset)
                lineTo(inset, size.height - inset)
            }
            close()
        }
        // The same hard shadow as the lettering beside it, then the symbol itself.
        translate(2f, 2f) { drawPath(path, Color.Black.copy(alpha = 0.55f)) }
        drawPath(path, color)
    }
}

/** "PLAY ▶" or "STOP ■", as a VCR shows it in the corner of the picture. */
@Composable
fun DeckMark(playing: Boolean, modifier: Modifier = Modifier, size: TextUnit = 22.sp, color: Color = Color.White) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(if (playing) "PLAY" else "STOP", style = osdStyle(size, color))
        Box(Modifier.size(6.dp))
        DeckSymbol(playing, color, (size.value * 0.62f).dp)
    }
}

/** Lettering banded top to bottom in [colors], like an arcade marquee, over a hard [shadow]. */
@Composable
fun StripedText(text: String, style: TextStyle, colors: List<Color>, shadow: Color, modifier: Modifier = Modifier) {
    Box(modifier) {
        Text(text, style = style.copy(color = shadow), maxLines = 1, softWrap = false, modifier = Modifier.offset(3.dp, 3.dp))
        Text(
            text,
            style = style.copy(color = Color.White),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val band = size.height / colors.size
                    colors.forEachIndexed { i, color ->
                        drawRect(color, Offset(0f, i * band), Size(size.width, band + 1f), blendMode = BlendMode.SrcAtop)
                    }
                },
        )
    }
}

private class Pixel(val angle: Float, val reach: Float, val size: Float, val tint: Int)

/**
 * A burst of square pixels from the middle of [modifier]'s area each time [trigger] changes,
 * moving in whole-pixel steps like an 8-bit explosion.
 */
@Composable
fun PixelBurst(trigger: Int, colors: List<Color>, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(1f) }
    val pixels = remember(trigger) {
        val random = Random(trigger)
        List(26) {
            Pixel(
                angle = random.nextFloat() * 2f * PI.toFloat(),
                reach = 0.4f + random.nextFloat() * 0.6f,
                size = 6f + random.nextInt(3) * 3f,
                tint = random.nextInt(colors.size.coerceAtLeast(1)),
            )
        }
    }
    LaunchedEffect(trigger) {
        if (trigger > 0) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(900, easing = LinearEasing))
        }
    }
    Canvas(modifier) {
        // Advance in eight chunky frames rather than smoothly.
        val p = (progress.value * 8f).roundToInt() / 8f
        if (p >= 1f || colors.isEmpty()) return@Canvas
        val grid = 4.dp.toPx()
        val maxReach = size.minDimension * 0.5f
        pixels.forEach { pixel ->
            val distance = maxReach * pixel.reach * p
            val side = (pixel.size * density * (1f - 0.5f * p) / grid).roundToInt().coerceAtLeast(1) * grid
            val x = ((center.x + cos(pixel.angle) * distance) / grid).roundToInt() * grid
            val y = ((center.y + sin(pixel.angle) * distance) / grid).roundToInt() * grid
            drawRect(colors[pixel.tint % colors.size], Offset(x - side / 2f, y - side / 2f), Size(side, side))
        }
    }
}

/** Coarse snow for a television between channels: twice as wide and tall as it is drawn, so it can jump about. */
private val snowTile: ImageBitmap by lazy {
    val w = 160
    val h = 112
    val random = Random(41)
    val pixels = IntArray(w * h) {
        val v = random.nextInt(0, 256)
        android.graphics.Color.argb(255, v, v, v)
    }
    Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Whether the television has been switched on in this run of the app: turning the phone round does not do it again. */
private var switchedOn = false

/** The green of a television's channel number. */
private val ChannelGreen = Color(0xFF7CFF4F)

/**
 * The television's moods, for the picture inside [Tube]: it switches on the way a cathode-ray
 * tube did, a bright line opening out into the picture; and changing channel brings a burst
 * of snow, with the channel's number in green in the corner for a moment.
 */
@Composable
fun rememberTubeEffects(): Modifier {
    val vibe = LocalVibe.current
    val power = remember { Animatable(if (switchedOn) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!switchedOn) {
            switchedOn = true
            delay(150)
            power.animateTo(1f, tween(750, easing = LinearEasing))
        }
    }
    val snow = remember { Animatable(0f) }
    val badge = remember { Animatable(0f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(vibe) {
        if (first) {
            first = false
            return@LaunchedEffect
        }
        badge.snapTo(1f)
        snow.snapTo(1f)
        snow.animateTo(0f, tween(450, easing = LinearEasing))
        delay(900)
        badge.animateTo(0f, tween(300))
    }
    val measurer = rememberTextMeasurer()
    val label = vibe.channelName
    return Modifier.drawWithContent {
        val p = power.value
        when {
            p >= 1f -> drawContent()
            p < 0.3f -> {
                // A dot of light that stretches into a line across the middle.
                drawRect(Color.Black)
                val w = size.width * (p / 0.3f)
                val h = 3.dp.toPx()
                drawRect(Color.White, Offset((size.width - w) / 2f, (size.height - h) / 2f), Size(w, h))
            }
            else -> {
                // The line opens out into the picture, washed white at first.
                val q = (p - 0.3f) / 0.7f
                val open = 1f - (1f - q) * (1f - q) * (1f - q)
                val h = size.height * open
                val top = (size.height - h) / 2f
                drawRect(Color.Black)
                clipRect(0f, top, size.width, top + h) { this@drawWithContent.drawContent() }
                drawRect(Color.White.copy(alpha = 0.85f * (1f - q)), Offset(0f, top), Size(size.width, h))
            }
        }
        val s = snow.value
        if (s > 0f) {
            // A different patch of snow each frame, drawn in big square pixels.
            val random = Random((s * 10_000).toInt())
            val tile = snowTile
            val srcW = tile.width / 2
            val srcH = tile.height / 2
            drawImage(
                tile,
                srcOffset = IntOffset(random.nextInt(srcW), random.nextInt(srcH)),
                srcSize = IntSize(srcW, srcH),
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                alpha = (s * 1.4f).coerceAtMost(1f) * 0.9f,
                filterQuality = FilterQuality.None,
            )
        }
        val b = badge.value
        if (b > 0f) {
            val text = measurer.measure(label, osdStyle(30.sp, ChannelGreen.copy(alpha = b)))
            drawText(text, topLeft = Offset(size.width - text.size.width - 14.dp.toPx(), 8.dp.toPx()))
        }
    }
}
