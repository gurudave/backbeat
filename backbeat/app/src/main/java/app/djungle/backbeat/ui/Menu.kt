package app.djungle.backbeat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.djungle.backbeat.ui.theme.Arcade
import app.djungle.backbeat.ui.theme.LocalVibe
import app.djungle.backbeat.ui.theme.Vcr

/** The pages of settings under the screen, chosen from the menu. */
enum class Page(val label: String) { BEAT("beat"), EAR("ear"), PRACTICE("practice"), TAPES("tapes"), SET_UP("set up") }

/**
 * The line under a page's name in the menu: what is set there now. [lit] when it changes what
 * you hear or what the app does (a ramp, silent bars, the Ear listening), so it is not forgotten.
 */
data class PageNote(val text: String, val lit: Boolean = false)

/**
 * The bar along the top of the settings: the menu key and the name of the page on show.
 * Anywhere on it opens the menu.
 */
@Composable
fun PageBar(page: Page, onMenu: () -> Unit, modifier: Modifier = Modifier) {
    val vibe = LocalVibe.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        modifier
            .fillMaxWidth()
            .clickable(source, indication = null, role = Role.Button, onClickLabel = "open the menu", onClick = onMenu)
            .semantics {
                contentDescription = "menu"
                stateDescription = "${page.label} page"
            }
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RetroBlock(
            fill = vibe.surface,
            held = pressed,
            depth = 3.dp,
            contentPadding = PaddingValues(horizontal = 11.dp, vertical = 10.dp),
        ) {
            MenuGlyph(vibe.ink)
        }
        Text(
            page.label.uppercase(),
            color = vibe.ink,
            fontFamily = Arcade,
            fontSize = 13.sp,
            maxLines = 1,
            modifier = Modifier.padding(start = 14.dp),
        )
    }
}

/** Three chunky bars, the sign for a menu, drawn on the pixel grid like everything else. */
@Composable
private fun MenuGlyph(color: Color) {
    Canvas(Modifier.size(width = 18.dp, height = 14.dp)) {
        val bar = 3.dp.toPx()
        val gap = (size.height - 3 * bar) / 2f
        for (i in 0 until 3) drawRect(color, Offset(0f, i * (bar + gap)), Size(size.width, bar))
    }
}

/**
 * The menu: the pages of settings, slid in from the left like the set-up menu of a VCR, with a
 * cursor at the page on show and, under each, what is set there now. Choosing one shows it and
 * puts the menu away; so do BACK, the x and a tap beside the menu.
 */
@Composable
fun PageMenu(
    open: Boolean,
    page: Page,
    note: (Page) -> PageNote,
    onPage: (Page) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(open, onClose)
    AnimatedVisibility(open, enter = fadeIn(tween(160)), exit = fadeOut(tween(160))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = "close the menu", onClick = onClose),
        )
    }
    AnimatedVisibility(
        open,
        enter = slideInHorizontally(tween(200)) { -it },
        exit = slideOutHorizontally(tween(160)) { -it },
    ) {
        MenuSheet(page, note, onPage, onClose)
    }
}

@Composable
private fun MenuSheet(page: Page, note: (Page) -> PageNote, onPage: (Page) -> Unit, onClose: () -> Unit) {
    val vibe = LocalVibe.current
    val shadow = blockShadow()
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    }
    BoxWithConstraints(
        Modifier
            .fillMaxHeight()
            .fillMaxWidth(0.86f)
            .widthIn(max = 360.dp)
            // The same hard shadow as every block, along the open edge.
            .padding(end = 6.dp)
            .drawBehind { drawRect(shadow, Offset(6.dp.toPx(), 0f), size) }
            .background(vibe.background)
            .scanlines(Color.Black.copy(alpha = if (vibe.isDark) 0.22f else 0.04f))
            .border(2.dp, shadow)
            // Taps on the sheet itself stay on it, rather than closing it from behind.
            .pointerInput(Unit) { detectTapGestures { } }
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
    ) {
        // The pages at the top and the maker's plate at the foot, scrolling as one if the window is low.
        Column(
            Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StripedText("MENU", TextStyle(fontFamily = Arcade, fontSize = 18.sp), vibe.stripes, shadow, Modifier.weight(1f))
                    RetroChip("x", selected = false, onClick = onClose, modifier = Modifier.semantics { contentDescription = "close the menu" })
                }
                ArcadeStripes(vibe.stripes, Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 10.dp), band = 2.dp, gap = 1.dp)
                val blink = rememberBlink(530L)
                Page.entries.forEach { each ->
                    MenuItem(each, note(each), current = each == page, cursor = blink, onClick = { onPage(each) })
                }
            }
            Text(
                "BACKBEAT $version\nno adverts, no accounts, no internet.",
                color = vibe.ink.copy(alpha = 0.5f),
                fontFamily = Vcr,
                fontSize = 18.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun MenuItem(page: Page, note: PageNote, current: Boolean, cursor: Boolean, onClick: () -> Unit) {
    val vibe = LocalVibe.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val fill = when {
        current -> vibe.primary
        pressed -> vibe.soft
        else -> Color.Transparent
    }
    val ink = if (current) vibe.onPrimary else vibe.ink
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(fill)
            .clickable(source, indication = null, onClick = onClick)
            // Read out whole, and found by the CI walk-through by its "... page" description.
            .semantics {
                contentDescription = "${page.label} page: ${note.text}"
                selected = current
            }
            .padding(start = 6.dp, end = 10.dp, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The cursor, blinking beside the page on show, as on a VCR's menu.
        Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) {
            if (current && cursor) DeckSymbol(true, ink, 13.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(page.label.uppercase(), color = ink, fontFamily = Vcr, fontSize = 30.sp, lineHeight = 30.sp, maxLines = 1)
            Text(
                note.text,
                color = when {
                    current -> ink.copy(alpha = if (note.lit) 1f else 0.75f)
                    note.lit -> vibe.accent
                    else -> vibe.ink.copy(alpha = 0.6f)
                },
                fontFamily = Vcr,
                fontSize = 18.sp,
                lineHeight = 19.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
