package app.djungle.backbeat.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.util.Log
import android.os.Vibrator
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import app.djungle.backbeat.TickService
import app.djungle.backbeat.data.AppSettings
import app.djungle.backbeat.data.HiScore
import app.djungle.backbeat.data.HiScores
import app.djungle.backbeat.data.Logbook
import app.djungle.backbeat.data.Options
import app.djungle.backbeat.data.Tape
import app.djungle.backbeat.engine.Accent
import app.djungle.backbeat.engine.Ear
import app.djungle.backbeat.engine.EarMode
import app.djungle.backbeat.engine.Groove
import app.djungle.backbeat.engine.Metronome
import app.djungle.backbeat.engine.Now
import app.djungle.backbeat.engine.Practice
import app.djungle.backbeat.engine.Scorecard
import app.djungle.backbeat.engine.TapTempo
import app.djungle.backbeat.ui.theme.BackbeatTheme
import app.djungle.backbeat.ui.theme.LocalVibe
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** The whole app: one screen, a television over five tabs. */
@Composable
fun BackbeatApp() {
    val context = LocalContext.current
    var vibe by remember { mutableStateOf(AppSettings.vibe(context)) }
    var groove by remember { mutableStateOf(AppSettings.groove(context)) }
    var practice by remember { mutableStateOf(AppSettings.practice(context)) }
    var options by remember { mutableStateOf(AppSettings.options(context)) }
    var tapes by remember { mutableStateOf(AppSettings.tapes(context)) }
    var hiScores by remember { mutableStateOf(AppSettings.hiScores(context)) }
    var nudge by remember { mutableIntStateOf(AppSettings.nudge(context)) }
    var tab by rememberSaveable { mutableStateOf(Tab.BEAT) }
    var stage by rememberSaveable { mutableStateOf(false) }
    BackHandler(stage) { stage = false }
    LaunchedEffect(stage) { Log.i("Backbeat", if (stage) "big screen open" else "big screen closed") }
    val engine by Metronome.state.collectAsStateWithLifecycle()
    val ear by Ear.state.collectAsStateWithLifecycle()
    val hits = Ear.hits.collectAsState()
    val live = Ear.live.collectAsState()
    val logRevision by Logbook.revision.collectAsStateWithLifecycle()
    val logbook = remember(logRevision) { AppSettings.logbook(context) }
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

    // Every change reaches the engine at once, and is remembered for next time. Not while the
    // Ear calibrates, which plays its own clicks: asked directly, since what is collected from
    // it can be a frame behind.
    SideEffect { if (Ear.mode != EarMode.CALIBRATE) Metronome.update(groove, practice) }
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

    // The microphone is asked for the first time the Ear is used, and whatever asked waits for it.
    fun micAllowed() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    var micGranted by remember { mutableStateOf(micAllowed()) }
    var afterMic by remember { mutableStateOf<(() -> Unit)?>(null) }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        if (granted) afterMic?.invoke() else say("the ear needs the microphone")
        afterMic = null
    }
    fun withMicrophone(action: () -> Unit) {
        if (micAllowed()) {
            micGranted = true
            action()
        } else {
            afterMic = action
            microphone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    fun calibrate() = withMicrophone { Ear.calibrate(context, groove, practice) }
    fun changeCoach(on: Boolean) {
        if (!on) {
            changeOptions(options.copy(coach = false))
            return
        }
        withMicrophone {
            changeOptions(options.copy(coach = true))
            // The first time, it listens to itself to learn the phone's delay, unless it cannot hear itself.
            if (ear.calibration == null && !engine.playing && !Ear.headphones(context)) {
                say("first, it listens to itself")
                Ear.calibrate(context, groove, practice)
            }
        }
    }

    // Counts STARTs, so the layout can unfold the picture for each one.
    var starts by remember { mutableIntStateOf(0) }
    fun startStop() {
        when {
            // STOP during calibration ends it, and puts everything back.
            ear.mode == EarMode.CALIBRATE -> Ear.stop()
            engine.playing -> Metronome.stop()
            else -> {
                // The tempo finder would only hear the click.
                if (ear.mode == EarMode.TEMPO) Ear.stop()
                starts++
                Metronome.update(groove, practice)
                Metronome.start(context)
                if (options.keepTicking) TickService.start(context)
            }
        }
    }

    // Playing along: the Ear listens from START to STOP, whenever "judge my timing" is on, and
    // only with the app on the screen.
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val onScreen = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val coaching = engine.playing && options.coach && micGranted && onScreen && ear.mode != EarMode.CALIBRATE
    LaunchedEffect(coaching) {
        if (coaching) {
            if (Ear.mode == EarMode.OFF) Ear.coach(context, groove.bpm)
        } else if (Ear.mode == EarMode.COACH) {
            Ear.stop()
        }
    }
    LaunchedEffect(ear.calibration) {
        ear.calibration?.let { AppSettings.setCalibration(context, it) }
    }
    var seenFailures by remember { mutableIntStateOf(Ear.state.value.calibrationFailures) }
    LaunchedEffect(ear.calibrationFailures) {
        if (ear.calibrationFailures > seenFailures) {
            seenFailures = ear.calibrationFailures
            // Without a calibration, judging would only mislead.
            if (ear.calibration == null && options.coach && !Ear.headphones(context)) changeOptions(options.copy(coach = false))
        }
    }

    // Tapes as a set list: step through them with the big screen's keys, or a pedal.
    val tapeIndex = tapes.indexOfFirst { it.groove == groove }
    fun stepTape(by: Int) {
        if (tapes.isEmpty()) {
            say("no tapes yet: record one on the tapes tab")
            return
        }
        val next = if (tapeIndex < 0) (if (by > 0) 0 else tapes.lastIndex) else Math.floorMod(tapeIndex + by, tapes.size)
        val tape = tapes[next]
        changeGroove(tape.groove)
        say("${tape.name} · ${tape.groove.bpm} bpm")
    }
    val onCommand by rememberUpdatedState { command: Command ->
        when (command) {
            Command.START_STOP -> startStop()
            Command.NEXT -> stepTape(1)
            Command.PREVIOUS -> stepTape(-1)
            Command.FASTER -> changeGroove(groove.withBpm(groove.bpm + 1))
            Command.SLOWER -> changeGroove(groove.withBpm(groove.bpm - 1))
            Command.TAP -> tapper.tap(SystemClock.uptimeMillis())?.let { changeGroove(groove.withBpm(it)) }
        }
        true
    }
    DisposableEffect(Unit) {
        Pedal.handler = { onCommand(it) }
        onDispose { Pedal.handler = null }
    }

    // The results of a session of playing along, until they are signed or put away.
    var results by remember { mutableStateOf<Scorecard?>(null) }
    var seenCards by remember { mutableIntStateOf(Ear.state.value.cards) }
    LaunchedEffect(ear.cards) {
        if (ear.cards > seenCards) {
            seenCards = ear.cards
            results = ear.card
        }
    }

    // The screen stays on while the metronome plays, or the Ear listens.
    val view = LocalView.current
    val awake = engine.playing || ear.mode != EarMode.OFF
    DisposableEffect(awake) {
        view.keepScreenOn = awake
        onDispose { view.keepScreenOn = false }
    }

    // Once a display frame, ask the engine what is being heard. Only drawing reads it.
    val now = remember { mutableStateOf<Now?>(null) }
    val latestOptions by rememberUpdatedState(options)
    val vibrator = remember { context.getSystemService(Vibrator::class.java) }
    // Each frame is drawn for the moment it reaches the glass, about one refresh after it starts.
    val framePeriod = remember(view) { (1e9 / (view.display?.refreshRate ?: 60f)).toLong() }
    LaunchedEffect(engine.playing) {
        now.value = null
        if (!engine.playing) return@LaunchedEffect
        var heard = -1L
        while (true) {
            // The display's own frame clock ticks evenly, where the time the code runs does not.
            val frameNanos = withFrameNanos { it }
            val n = Metronome.now(frameNanos + framePeriod)
            now.value = n
            if (n != null && n.serial != heard) {
                heard = n.serial
                val tick = n.tick
                if (latestOptions.buzz && tick.isBeat && tick.audible && tick.accent != Accent.MUTE) {
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
        Box(Modifier.fillMaxSize().background(colors.background)) {
            RetroBackdrop(Modifier.fillMaxSize())
            BackbeatLayout(
                header = { Header(engine.playing, onLogoTap = { burst++ }) },
                tube = { height, fold ->
                    Tube(
                        groove = groove,
                        practice = practice,
                        playing = engine.playing,
                        liveBpm = engine.bpm,
                        now = now,
                        counterSeconds = counterSeconds,
                        flash = options.flash,
                        onAccent = { changeGroove(groove.cycleAccent(it)) },
                        height = height,
                        fold = fold,
                        ear = EarShow(ear, hits, live),
                    )
                },
                tempo = {
                    TempoRow(
                        bpm = groove.bpm,
                        onBpm = { changeGroove(groove.withBpm(it)) },
                        onTap = { tapper.tap(SystemClock.uptimeMillis())?.let { changeGroove(groove.withBpm(it)) } },
                    )
                },
                tabs = { TabChips(tab) { tab = it } },
                panel = {
                    when (tab) {
                        Tab.BEAT -> BeatPanel(groove, ::changeGroove) { kit ->
                            changeGroove(groove.copy(kit = kit))
                            if (!engine.playing) Metronome.audition(kit, groove.volume)
                        }
                        Tab.EAR -> EarPanel(
                            ear = ear,
                            coach = options.coach,
                            onCoach = ::changeCoach,
                            onCalibrate = ::calibrate,
                            nudge = nudge,
                            onNudge = {
                                nudge = it
                                Ear.nudgeMillis = it
                                AppSettings.setNudge(context, it)
                            },
                            hiScores = hiScores,
                            onListen = {
                                if (ear.mode == EarMode.TEMPO) {
                                    Ear.stop()
                                } else {
                                    withMicrophone {
                                        // Unfold the picture, to show what it hears in big digits.
                                        starts++
                                        Ear.findTempo(context)
                                    }
                                }
                            },
                            onSetTempo = {
                                Ear.stop()
                                changeGroove(groove.withBpm(it))
                                say("tempo set: $it bpm")
                            },
                        )
                        Tab.PRACTICE -> PracticePanel(practice, groove, ::changePractice, logbook)
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
                            onRaise = { tape ->
                                val i = tapes.indexOfFirst { it.id == tape.id }
                                if (i > 0) {
                                    tapes = tapes.toMutableList().apply { add(i - 1, removeAt(i)) }
                                    AppSettings.setTapes(context, tapes)
                                }
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
                },
                transport = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TransportButton(engine.playing, onClick = ::startStop, modifier = Modifier.weight(1f))
                        StageKey(onClick = { stage = true })
                    }
                },
                panelKey = tab,
                unfold = starts,
            )

            PixelBurst(burst, colors.stripes, Modifier.fillMaxWidth().height(300.dp))
            ToastPill(
                toast,
                toastVisible,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp),
            )
            if (stage) {
                StageView(
                    groove = groove,
                    playing = engine.playing,
                    liveBpm = engine.bpm,
                    now = now,
                    tapeName = tapes.getOrNull(tapeIndex)?.name,
                    nextName = tapes.takeIf { it.size > 1 }?.let { list ->
                        val next = list[Math.floorMod(tapeIndex + 1, list.size)]
                        "${next.name} · ${next.groove.bpm} bpm"
                    },
                    onStartStop = ::startStop,
                    onPrevious = { stepTape(-1) },
                    onNext = { stepTape(1) },
                    onClose = { stage = false },
                )
            }
            // The scorecard waits until the big screen is put away: mid-set, it would only be in the way.
            results?.takeIf { !stage }?.let { card ->
                // BACK puts the scorecard away unsigned, rather than acting on what is behind it.
                BackHandler { results = null }
                ResultsCard(card, hiScores, AppSettings.lastInitials(context)) { initials ->
                    if (initials != null) {
                        val entry = HiScore(
                            initials = initials,
                            points = card.points,
                            rank = card.rank,
                            tight = (card.tightness * 100).roundToInt(),
                            bpm = card.bpm,
                            at = System.currentTimeMillis(),
                        )
                        hiScores = HiScores.insert(hiScores, entry)
                        AppSettings.setHiScores(context, hiScores)
                        AppSettings.setLastInitials(context, initials)
                        burst++
                        tab = Tab.EAR
                    }
                    results = null
                }
            }
        }
    }
}
