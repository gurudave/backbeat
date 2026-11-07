package app.djungle.backbeat.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import app.djungle.backbeat.ui.theme.LocalVibe

/** The chrome around the picture inside [Tube]: its padding and the block's shadow. */
private val TUBE_CHROME = 24.dp

/**
 * Lays the screen out to suit its shape, from six pieces the caller supplies. The settings
 * ([pageBar] over [panel]) sit on a shelf of their own, sunk a little below the television and
 * set apart from START, so it is plain which controls are always there and which are the page
 * of settings on show.
 *
 * - **Tall** (a phone held upright): the controls stay put and the page scrolls between them.
 *   Scrolling the page up first folds the television into a strip, handing its room to the
 *   settings; scrolling back to the top unfolds it, and so does each START ([unfold]).
 * - **Wide** (a phone on its side, a tablet): two panes, the television and tempo on the
 *   left, the settings and START on the right, each with the full height.
 * - **Short** (a small window): everything in one scrolling column.
 *
 * [tube] is given the height for its picture and how far it is folded (0 to 1).
 * [panelKey] is the page on show, so each one starts at its top. [initialFold] is for pictures
 * of the layout (previews and tests) that want the picture folded from the start.
 */
@Composable
fun BackbeatLayout(
    header: @Composable () -> Unit,
    tube: @Composable (height: Dp, fold: Float) -> Unit,
    tempo: @Composable () -> Unit,
    pageBar: @Composable () -> Unit,
    panel: @Composable () -> Unit,
    transport: @Composable () -> Unit,
    panelKey: Any,
    unfold: Int,
    modifier: Modifier = Modifier,
    initialFold: Float = 0f,
) {
    BoxWithConstraints(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
        val wide = maxWidth >= 600.dp && maxWidth > maxHeight
        val tall = !wide && maxHeight >= 560.dp
        when {
            wide -> Wide(header, tube, tempo, pageBar, panel, transport, panelKey)
            tall -> Tall(maxHeight, header, tube, tempo, pageBar, panel, transport, panelKey, unfold, initialFold)
            else -> Short(header, tube, tempo, pageBar, panel, transport)
        }
    }
}

@Composable
private fun Tall(
    height: Dp,
    header: @Composable () -> Unit,
    tube: @Composable (height: Dp, fold: Float) -> Unit,
    tempo: @Composable () -> Unit,
    pageBar: @Composable () -> Unit,
    panel: @Composable () -> Unit,
    transport: @Composable () -> Unit,
    panelKey: Any,
    unfold: Int,
    initialFold: Float,
) {
    val full = (height * 0.29f).coerceIn(TUBE_UNFOLDED_MIN, 260.dp)
    val density = LocalDensity.current
    val range by rememberUpdatedState(with(density) { (full - TUBE_FOLDED).toPx() })
    var fold by remember { mutableFloatStateOf(initialFold) }

    // The page's scrolling folds and unfolds the picture before (or after) the page itself moves.
    val folding = remember {
        object : NestedScrollConnection {
            /** Folds by [dy] pixels of scroll (negative folds), returning the pixels used. */
            fun take(dy: Float): Float {
                if (range <= 0f) return 0f
                val before = fold
                fold = (fold - dy / range).coerceIn(0f, 1f)
                return (before - fold) * range
            }

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (available.y < 0f) Offset(0f, take(available.y)) else Offset.Zero

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (available.y > 0f) Offset(0f, take(available.y)) else Offset.Zero

            // A picture left half folded settles to whichever end is nearer.
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val target = if (fold > 0.5f) 1f else 0f
                if (fold != target) animate(fold, target, animationSpec = tween(180)) { value, _ -> fold = value }
                return Velocity.Zero
            }
        }
    }
    LaunchedEffect(unfold) {
        if (fold > 0f) animate(fold, 0f, animationSpec = tween(260)) { value, _ -> fold = value }
    }
    // While the keyboard is up (naming a tape), the picture folds out of its way.
    val keyboard = WindowInsets.ime.getBottom(density) > 0
    val shownFold = if (keyboard) 1f else fold

    Column(Modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            header()
            tube(lerp(full, TUBE_FOLDED, shownFold), shownFold)
            tempo()
        }
        Shelf(Modifier.weight(1f).padding(top = 10.dp)) {
            pageBar()
            val scroll = key(panelKey) { rememberScrollState() }
            Column(Modifier.weight(1f).scrollFade(scroll).nestedScroll(folding).verticalScroll(scroll)) {
                panel()
                Box(Modifier.height(12.dp))
            }
        }
        Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp)) { transport() }
    }
}

@Composable
private fun Wide(
    header: @Composable () -> Unit,
    tube: @Composable (height: Dp, fold: Float) -> Unit,
    tempo: @Composable () -> Unit,
    pageBar: @Composable () -> Unit,
    panel: @Composable () -> Unit,
    transport: @Composable () -> Unit,
    panelKey: Any,
) {
    Row(Modifier.fillMaxSize().imePadding().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            Modifier.weight(1f).fillMaxHeight().padding(start = 16.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            header()
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val picture = maxHeight - TUBE_CHROME
                // A low window gets the folded strip's layout, so nothing is squeezed.
                val fold = ((TUBE_UNFOLDED_MIN - picture) / (TUBE_UNFOLDED_MIN - TUBE_FOLDED)).coerceIn(0f, 1f)
                tube(picture.coerceAtLeast(TUBE_FOLDED), fold)
            }
            tempo()
        }
        // START sits under the settings, where a right thumb finds it with the phone on its side.
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Shelf(Modifier.weight(1f).padding(end = 16.dp), framed = true) {
                pageBar()
                val scroll = key(panelKey) { rememberScrollState() }
                Column(Modifier.weight(1f).scrollFade(scroll).verticalScroll(scroll)) {
                    panel()
                    Box(Modifier.height(12.dp))
                }
            }
            Box(Modifier.padding(end = 16.dp, top = 12.dp, bottom = 10.dp)) { transport() }
        }
    }
}

@Composable
private fun Short(
    header: @Composable () -> Unit,
    tube: @Composable (height: Dp, fold: Float) -> Unit,
    tempo: @Composable () -> Unit,
    pageBar: @Composable () -> Unit,
    panel: @Composable () -> Unit,
    transport: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            header()
            tube(TUBE_UNFOLDED_MIN, 0f)
            tempo()
        }
        Shelf(Modifier.padding(top = 10.dp)) {
            pageBar()
            panel()
            Box(Modifier.height(12.dp))
        }
        Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp)) { transport() }
    }
}

/**
 * The shelf the settings sit on: a band a shade darker than the screen, edged above and below,
 * with the television's shadow falling across its top as if the set stood over it. [framed]
 * draws it as a tray with its own four sides, for a pane that does not span the screen.
 */
@Composable
private fun Shelf(modifier: Modifier = Modifier, framed: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val vibe = LocalVibe.current
    val tray = if (vibe.isDark) Color.Black.copy(alpha = 0.3f) else vibe.ink.copy(alpha = 0.06f)
    val edge = if (vibe.isDark) vibe.soft else vibe.ink.copy(alpha = 0.22f)
    val shade = if (vibe.isDark) Color.Black.copy(alpha = 0.45f) else vibe.ink.copy(alpha = 0.12f)
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier
            .fillMaxWidth()
            .then(if (framed) Modifier.clip(shape).border(2.dp, edge, shape) else Modifier)
            .drawBehind {
                drawRect(tray)
                val fall = 12.dp.toPx()
                drawRect(Brush.verticalGradient(listOf(shade, Color.Transparent), startY = 0f, endY = fall), size = Size(size.width, fall))
                if (!framed) {
                    val line = 2.dp.toPx()
                    drawRect(edge, size = Size(size.width, line))
                    drawRect(edge, Offset(0f, size.height - line), Size(size.width, line))
                }
            },
        content = content,
    )
}

/**
 * Fades the settings out at an edge that has more beyond it, rather than cutting a line of
 * lettering in half against the page's name or START, and so shows there is more to scroll to.
 */
private fun Modifier.scrollFade(scroll: ScrollState, edge: Dp = 22.dp) = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val e = edge.toPx()
        if (scroll.value > 0) {
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = 0f, endY = e),
                size = Size(size.width, e),
                blendMode = BlendMode.DstIn,
            )
        }
        if (scroll.value < scroll.maxValue) {
            drawRect(
                Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = size.height - e, endY = size.height),
                topLeft = Offset(0f, size.height - e),
                size = Size(size.width, e),
                blendMode = BlendMode.DstIn,
            )
        }
    }
