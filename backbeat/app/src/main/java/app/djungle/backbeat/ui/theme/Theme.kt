package app.djungle.backbeat.ui.theme

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.djungle.backbeat.R

/**
 * The app's colour themes, presented as TV channels, flipped between on the SET UP tab. They
 * are the same six channels as Djungleskwidgets and Funhouse, lifted from the retro mood
 * board in Djungleskwidgets' repository, so the three apps feel like one family.
 */
enum class Vibe(
    val label: String,
    val background: Color,
    val surface: Color,
    val primary: Color,
    val onPrimary: Color,
    val soft: Color,
    val ink: Color,
    val accent: Color,
    /** The cabinet stripes: drawn under the logo, on buttons and on the channel tiles. */
    val stripes: List<Color>,
    val isDark: Boolean = true,
) {
    /** Black cabinets with red, orange and yellow side-art stripes. */
    ARCADE(
        "arcade", Color(0xFF120D16), Color(0xFF1F1724), Color(0xFFFF5B2E), Color(0xFF1A0B06),
        Color(0xFF3A2A40), Color(0xFFFFEBCB), Color(0xFFFFC233),
        listOf(Color(0xFFFFC233), Color(0xFFFF7A1A), Color(0xFFE8322B)),
    ),

    /** A maze in electric blue on a black tube, with a yellow hero and four ghosts. */
    HIGH_SCORE(
        "high score", Color(0xFF05060F), Color(0xFF0E1233), Color(0xFFFFD23F), Color(0xFF120E00),
        Color(0xFF1E2759), Color(0xFFE6EBFF), Color(0xFF4A6BFF),
        listOf(Color(0xFFFF4D4D), Color(0xFFFF8AD8), Color(0xFF3FE0FF), Color(0xFFFFB347)),
    ),

    /** Neon pink over a dim rink, and the orange of a well-loved pair of skate wheels. */
    ROLLER_RINK(
        "roller rink", Color(0xFF1C0D15), Color(0xFF2D1622), Color(0xFFFF4F8E), Color(0xFF26060F),
        Color(0xFF4A2537), Color(0xFFFFE4EC), Color(0xFFFF9A2E),
        listOf(Color(0xFFFF9A2E), Color(0xFFFF4F8E), Color(0xFF3CC6C0)),
    ),

    /** A dusty-blue camcorder tape, white on-screen lettering and a red record light. */
    CAMCORDER(
        "camcorder", Color(0xFF16212A), Color(0xFF22313D), Color(0xFFEFE8D8), Color(0xFF16212A),
        Color(0xFF34495A), Color(0xFFEEF2F3), Color(0xFFE5483F),
        listOf(Color(0xFFE5483F), Color(0xFFEFE8D8), Color(0xFF5FA3A3)),
    ),

    /** A cherry-red convertible at the lights, faded to the colour of an old print. */
    SUNDAY_DRIVE(
        "sunday drive", Color(0xFFEDE5CB), Color(0xFFF7F1DE), Color(0xFFA3302A), Color(0xFFFBF3E0),
        Color(0xFFDCD0AE), Color(0xFF3A2E26), Color(0xFF4F8E82),
        listOf(Color(0xFFE59A3A), Color(0xFFA3302A), Color(0xFF4F8E82)),
        isDark = false,
    ),

    /** A crate of old LPs: worn paper, terracotta, mustard and sleeve brown. */
    RECORD_SHOP(
        "record shop", Color(0xFFE9DCC2), Color(0xFFF4EBD8), Color(0xFFB4532E), Color(0xFFFFF4E0),
        Color(0xFFD8C7A3), Color(0xFF3B2A20), Color(0xFFC99A2E),
        listOf(Color(0xFFC99A2E), Color(0xFFB4532E), Color(0xFF6B3E26)),
        isDark = false,
    ),
    ;

    /** Channels are numbered from one, like a television's. */
    val channel: Int get() = ordinal + 1

    /** "CH 03", as a television would put it. */
    val channelName: String get() = "CH " + channel.toString().padStart(2, '0')
}

/**
 * VT323: the blocky lettering of a VCR's on-screen display. It has a single weight, so the
 * same file stands in for bold too, which stops Android smearing a fake bold over the pixels.
 */
val Vcr = FontFamily(
    Font(R.font.vt323, FontWeight.Normal),
    Font(R.font.vt323, FontWeight.Medium),
    Font(R.font.vt323, FontWeight.Bold),
)

/** Press Start 2P: the lettering of an arcade high-score table. Single weight, as above. */
val Arcade = FontFamily(
    Font(R.font.press_start, FontWeight.Normal),
    Font(R.font.press_start, FontWeight.Bold),
)

val LocalVibe = staticCompositionLocalOf { Vibe.ARCADE }

/** VT323 is small for its size, so everything is set a few points larger than usual. */
private val BackbeatTypography = Typography().run {
    Typography(
        displayLarge = displayLarge.copy(fontFamily = Arcade),
        displayMedium = displayMedium.copy(fontFamily = Arcade),
        displaySmall = displaySmall.copy(fontFamily = Arcade),
        headlineLarge = headlineLarge.copy(fontFamily = Arcade),
        headlineMedium = headlineMedium.copy(fontFamily = Arcade),
        headlineSmall = headlineSmall.copy(fontFamily = Arcade, fontSize = 16.sp, lineHeight = 24.sp),
        titleLarge = titleLarge.copy(fontFamily = Vcr, fontSize = 28.sp),
        titleMedium = titleMedium.copy(fontFamily = Vcr, fontSize = 24.sp),
        titleSmall = titleSmall.copy(fontFamily = Vcr, fontSize = 20.sp),
        bodyLarge = bodyLarge.copy(fontFamily = Vcr, fontSize = 22.sp, lineHeight = 26.sp),
        bodyMedium = bodyMedium.copy(fontFamily = Vcr, fontSize = 20.sp, lineHeight = 24.sp),
        bodySmall = bodySmall.copy(fontFamily = Vcr, fontSize = 18.sp, lineHeight = 22.sp),
        labelLarge = labelLarge.copy(fontFamily = Vcr, fontSize = 20.sp),
        labelMedium = labelMedium.copy(fontFamily = Vcr, fontSize = 18.sp),
        labelSmall = labelSmall.copy(fontFamily = Vcr, fontSize = 16.sp),
    )
}

@Composable
fun BackbeatTheme(vibe: Vibe, content: @Composable () -> Unit) {
    val activity = LocalContext.current as? ComponentActivity
    LaunchedEffect(vibe) {
        val clear = Color.Transparent.toArgb()
        val bars = if (vibe.isDark) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear)
        activity?.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
    }
    val colors = if (vibe.isDark) {
        darkColorScheme(
            primary = vibe.primary, onPrimary = vibe.onPrimary, secondary = vibe.accent,
            background = vibe.background, onBackground = vibe.ink,
            surface = vibe.surface, onSurface = vibe.ink,
            surfaceVariant = vibe.soft, onSurfaceVariant = vibe.ink,
            surfaceContainerHigh = vibe.surface, outline = vibe.soft,
        )
    } else {
        lightColorScheme(
            primary = vibe.primary, onPrimary = vibe.onPrimary, secondary = vibe.accent,
            background = vibe.background, onBackground = vibe.ink,
            surface = vibe.surface, onSurface = vibe.ink,
            surfaceVariant = vibe.soft, onSurfaceVariant = vibe.ink,
            surfaceContainerHigh = vibe.surface, outline = vibe.soft,
        )
    }
    CompositionLocalProvider(LocalVibe provides vibe) {
        MaterialTheme(colorScheme = colors, typography = BackbeatTypography, content = content)
    }
}
