package app.djungle.backbeat.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.djungle.backbeat.TickService
import app.djungle.backbeat.data.AppSettings
import app.djungle.backbeat.data.Options
import app.djungle.backbeat.data.Tape
import app.djungle.backbeat.engine.Accent
import app.djungle.backbeat.engine.Groove
import app.djungle.backbeat.engine.Metronome
import app.djungle.backbeat.engine.Now
import app.djungle.backbeat.engine.Practice
import app.djungle.backbeat.engine.TapTempo
import app.djungle.backbeat.ui.theme.BackbeatTheme
import app.djungle.backbeat.ui.theme.LocalVibe
import kotlinx.coroutines.delay

/** The whole app: one screen, a television over four tabs. */
@Composable
fun BackbeatApp() {
    val context = LocalContext.current
    var vibe by remember { mutableStateOf(AppSettings.vibe(context)) }
    var groove by remember { mutableStateOf(AppSettings.groove(context)) }
    var practice by remember { mutableStateOf(AppSettings.practice(context)) }
    var options by remember { mutableStateOf(AppSettings.options(context)) }
    var tapes by remember { mutableStateOf(AppSettings.tapes(context)) }
    var tab by rememberSaveable { mutableStateOf(Tab.BEAT) }
    val engine by Metronome.state.collectAsStateWithLifecycle()
    val tapper = remember { TapTempo() }

    var burst by remember { mutableIntStateOf(0) }
    var toast by remember { mutableStateOf("") }
    var toastShown by remember { mutableIntStateOf(0) }
    var toastVisible by remember { mutableStateOf(false) }
    fun say(message: String) {
        toast = message
        toastVisible = true
        toastShown++
    }
    LaunchedEffect(toastShown) {
        delay(3000)
        toastVisible = false
    }

    // Every change reaches the engine at once, and is remembered for next time.
    SideEffect { Metronome.update(groove, practice) }
    fun changeGroove(next: Groove) {
        groove = next
        AppSettings.setGroove(context, next)
    }
    fun changePractice(next: Practice) {
        practice = next
        AppSettings.setPractice(context, next)
    }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun changeOptions(next: Options) {
        if (next.keepTicking && !options.keepTicking) {
            // The background service shows a notification; ask once if that is allowed.
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (engine.playing) TickService.start(context)
        }
        if (!next.keepTicking && options.keepTicking) TickService.end(context)
        options = next
        AppSettings.setOptions(context, next)
    }

    fun startStop() {
        if (engine.playing) {
            Metronome.stop()
        } else {
            Metronome.update(groove, practice)
            Metronome.start(context)
            if (options.keepTicking) TickService.start(context)
        }
    }

    // The screen stays on while the metronome plays.
    val view = LocalView.current
    DisposableEffect(engine.playing) {
        view.keepScreenOn = engine.playing
        onDispose { view.keepScreenOn = false }
    }

    // Once a display frame, ask the engine what is being heard. Only drawing reads it.
    val now = remember { mutableStateOf<Now?>(null) }
    val latestOptions by rememberUpdatedState(options)
    val vibrator = remember { context.getSystemService(Vibrator::class.java) }
    LaunchedEffect(engine.playing) {
        now.value = null
        if (!engine.playing) return@LaunchedEffect
        var heard = -1L
        while (true) {
            withFrameNanos { }
            val n = Metronome.now()
            now.value = n
            if (n != null && n.serial != heard) {
                heard = n.serial
                val tick = n.tick
                if (latestOptions.buzz && tick.step == 0 && tick.audible && tick.accent != Accent.MUTE) {
                    val strong = tick.accent == Accent.STRONG
                    runCatching { vibrator?.vibrate(VibrationEffect.createOneShot(if (strong) 40L else 20L, if (strong) 255 else 120)) }
                }
            }
        }
    }
    val counterSeconds by remember { derivedStateOf { (now.value?.elapsedMillis ?: 0L) / 1000L } }

    // Applause, of a sort, for finishing a practice or reaching a ramp's target.
    var seenTimeUps by remember { mutableIntStateOf(Metronome.state.value.timeUps) }
    LaunchedEffect(engine.timeUps) {
        if (engine.timeUps > seenTimeUps) {
            seenTimeUps = engine.timeUps
            burst++
            say("time's up. well played!")
        }
    }
    val reached = engine.playing && practice.ramp && engine.bpm == practice.rampTarget && groove.bpm != practice.rampTarget
    LaunchedEffect(reached) {
        if (reached) {
            burst++
            say("target reached: ${practice.rampTarget} bpm")
        }
    }

    BackbeatTheme(vibe) {
        val colors = LocalVibe.current
        BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
            RetroBackdrop(Modifier.fillMaxSize())
            // On a tall screen the controls stay put and only the tab scrolls; on a short one
            // (or turned sideways) everything scrolls together.
            val roomy = maxHeight >= 640.dp
            val tubeHeight = if (roomy) (maxHeight * 0.3f).coerceIn(200.dp, 280.dp) else 200.dp
            Column(
                Modifier
                    .fillMaxSize()
                    .then(if (roomy) Modifier else Modifier.verticalScroll(rememberScrollState()))
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                Column(
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Header(engine.playing, counterSeconds, onLogoTap = { burst++ })
                    Tube(
                        groove = groove,
                        practice = practice,
                        playing = engine.playing,
                        liveBpm = engine.bpm,
                        now = now,
                        flash = options.flash,
                        onAccent = { changeGroove(groove.cycleAccent(it)) },
                        height = tubeHeight,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RepeatKey("-", "slower", onStep = { changeGroove(groove.withBpm(groove.bpm - 1)) })
                        TempoDial(groove.bpm, onChange = { changeGroove(groove.withBpm(it)) }, modifier = Modifier.weight(1f))
                        RepeatKey("+", "faster", onStep = { changeGroove(groove.withBpm(groove.bpm + 1)) })
                        TapKey(onTap = {
                            tapper.tap(SystemClock.uptimeMillis())?.let { changeGroove(groove.withBpm(it)) }
                        })
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Tab.entries.forEach { RetroChip(it.label, selected = tab == it, onClick = { tab = it }) }
                }
                // Each tab starts at its top, rather than wherever the last one was scrolled to.
                val panelScroll = key(tab) { rememberScrollState() }
                Column(
                    if (roomy) Modifier.weight(1f).verticalScroll(panelScroll) else Modifier,
                ) {
                    when (tab) {
                        Tab.BEAT -> BeatPanel(groove, ::changeGroove) { kit ->
                            changeGroove(groove.copy(kit = kit))
                            if (!engine.playing) Metronome.audition(kit, groove.volume)
                        }
                        Tab.PRACTICE -> PracticePanel(practice, groove, ::changePractice)
                        Tab.TAPES -> TapesPanel(
                            tapes = tapes,
                            groove = groove,
                            playing = engine.playing,
                            onRecord = {
                                val name = "tape " + (tapes.size + 1).toString().padStart(2, '0')
                                tapes = listOf(Tape(System.currentTimeMillis(), name, groove)) + tapes
                                AppSettings.setTapes(context, tapes)
                                say("recorded on $name")
                            },
                            onLoad = { tape ->
                                changeGroove(tape.groove)
                                say("loaded ${tape.name}")
                            },
                            onRename = { tape, name ->
                                tapes = tapes.map { if (it.id == tape.id) it.copy(name = name) else it }
                                AppSettings.setTapes(context, tapes)
                            },
                            onErase = { tape ->
                                tapes = tapes.filterNot { it.id == tape.id }
                                AppSettings.setTapes(context, tapes)
                                say("erased ${tape.name}")
                            },
                        )
                        Tab.SET_UP -> SetUpPanel(
                            vibe = vibe,
                            onVibe = {
                                vibe = it
                                AppSettings.setVibe(context, it)
                            },
                            options = options,
                            onOptions = ::changeOptions,
                        )
                    }
                    Box(Modifier.height(12.dp))
                }
                TransportButton(
                    engine.playing,
                    onClick = ::startStop,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
                )
            }

            PixelBurst(burst, colors.stripes, Modifier.fillMaxWidth().height(300.dp))
            ToastPill(
                toast,
                toastVisible,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp),
            )
        }
    }
}
