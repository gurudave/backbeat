package app.djungle.backbeat.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.djungle.backbeat.data.HiScore
import app.djungle.backbeat.data.HiScores
import app.djungle.backbeat.engine.Calibration
import app.djungle.backbeat.engine.EarMode
import app.djungle.backbeat.engine.EarState
import app.djungle.backbeat.engine.Grade
import app.djungle.backbeat.engine.Scorecard
import app.djungle.backbeat.engine.Spread
import app.djungle.backbeat.ui.theme.Arcade
import app.djungle.backbeat.ui.theme.LocalVibe
import app.djungle.backbeat.ui.theme.Vcr
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the calibration found, in a line. */
private fun Calibration.describe(): String {
    val late = -offsetMillis
    val by = abs(late).roundToInt()
    return when {
        by == 0 -> "calibrated: the mic is right on time"
        late > 0 -> "calibrated: the mic hears $by ms late. allowed for."
        else -> "calibrated: the mic hears $by ms early. allowed for."
    }
}

/**
 * The Ear: playing along with the metronome while it listens and scores every hit, the
 * hi-score table, and a tempo finder for music playing nearby.
 */
@Composable
fun EarPanel(
    ear: EarState,
    coach: Boolean,
    onCoach: (Boolean) -> Unit,
    onCalibrate: () -> Unit,
    nudge: Int,
    onNudge: (Int) -> Unit,
    hiScores: List<HiScore>,
    onListen: () -> Unit,
    onSetTempo: (Int) -> Unit,
) {
    val vibe = LocalVibe.current
    val blink = rememberBlink(520L)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ToggleRow("judge my timing", "it listens as you play along, and scores every hit against the click", coach, onCoach)
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            val calibrating = ear.mode == EarMode.CALIBRATE
            Text(
                when {
                    calibrating -> if (blink) "listening to itself..." else ""
                    ear.calibration != null -> ear.calibration.describe()
                    else -> "not calibrated yet"
                }.uppercase(),
                color = vibe.ink.copy(alpha = 0.8f),
                fontFamily = Vcr,
                fontSize = 18.sp,
                lineHeight = 20.sp,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            RetroChip(if (ear.calibration == null) "calibrate" else "again", selected = calibrating, onClick = { if (!calibrating) onCalibrate() })
        }
        StepperRow("fine tune", nudge, -50..50, onNudge) { if (it == 0) "0 ms" else (if (it > 0) "+$it ms" else "$it ms") }
        ear.problem?.let { Problem(it) }
        Hint(
            "through the speaker it hears you best in silent bars and rests, or playing louder than the click. " +
                "with headphones it hears every beat. calibrate once, on the speaker, somewhere quiet.",
        )

        Box(Modifier.padding(top = 8.dp)) { SectionTitle("hi-scores", Modifier.padding(horizontal = 20.dp)) }
        if (hiScores.isEmpty()) {
            Hint("no scores yet. turn on 'judge my timing', press start and play along for a bit.")
        } else {
            HiScoreTable(hiScores, Modifier.padding(horizontal = 16.dp))
        }

        Box(Modifier.padding(top = 8.dp)) { SectionTitle("what tempo is this?", Modifier.padding(horizontal = 20.dp)) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            val listening = ear.mode == EarMode.TEMPO
            RetroBlock(
                fill = if (listening) vibe.accent else vibe.surface,
                onClick = onListen,
                held = listening,
                depth = 3.dp,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RecLight(!listening || blink)
                    Text(
                        if (listening) "  STOP" else "  LISTEN",
                        color = if (listening) vibe.onPrimary else vibe.ink,
                        fontFamily = Vcr,
                        fontSize = 20.sp,
                    )
                }
            }
            val guess = ear.tempo
            Text(
                when {
                    guess == null && listening -> "  listening..."
                    guess == null -> ""
                    ear.found -> "  found ${guess.rounded} bpm"
                    else -> "  ${guess.rounded} bpm?"
                }.uppercase(),
                color = vibe.accent,
                fontFamily = Vcr,
                fontSize = 22.sp,
                maxLines = 1,
            )
        }
        ear.tempo?.let { guess ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RetroChip("set ${guess.rounded}", selected = false, onClick = { onSetTempo(guess.rounded) })
                guess.alternatives.forEach { RetroChip("$it", selected = false, onClick = { onSetTempo(it) }) }
            }
        }
        Hint("hold the phone near the music. it needs a few seconds of a steady beat, and the metronome stops while it listens.")
    }
}

@Composable
private fun Problem(text: String) {
    Text(
        text.uppercase(),
        color = gradeColor(Grade.MISS, LocalVibe.current.isDark),
        fontFamily = Vcr,
        fontSize = 18.sp,
        lineHeight = 20.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        color = LocalVibe.current.ink.copy(alpha = 0.6f),
        fontFamily = Vcr,
        fontSize = 18.sp,
        lineHeight = 20.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
    )
}

/** The table, as a cabinet shows it between games: place, initials, score, rank. */
@Composable
fun HiScoreTable(scores: List<HiScore>, modifier: Modifier = Modifier, highlight: HiScore? = null) {
    val vibe = LocalVibe.current
    RetroBlock(modifier.fillMaxWidth(), fill = vibe.background, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        Column(Modifier.fillMaxWidth()) {
            scores.forEachIndexed { i, score ->
                val place = when (i) {
                    0 -> "1ST"
                    1 -> "2ND"
                    2 -> "3RD"
                    else -> "${i + 1}TH"
                }
                val lit = score == highlight
                val color = if (i == 0 || lit) vibe.accent else vibe.ink
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(place, color = color, fontFamily = Arcade, fontSize = 10.sp, modifier = Modifier.width(44.dp))
                    Text(score.initials, color = color, fontFamily = Arcade, fontSize = 10.sp, modifier = Modifier.width(48.dp))
                    Text(score.points.toString().padStart(6, '0'), color = color, fontFamily = Arcade, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    Text(
                        "${score.rank}  ${score.tight}%  ${score.bpm}",
                        color = color.copy(alpha = 0.8f),
                        fontFamily = Vcr,
                        fontSize = 18.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * The end of a session of playing along, as an arcade game ends: the rank, the score, the
 * grades, and whether the player rushed or dragged with the click and on their own. A score
 * that makes the table asks for three initials, set letter by letter with the arrows.
 */
@Composable
fun ResultsCard(
    card: Scorecard,
    table: List<HiScore>,
    initials: String,
    onDone: (initials: String?) -> Unit,
) {
    val vibe = LocalVibe.current
    val qualifies = HiScores.qualifies(table, card.points) && card.rank != "-"
    var letters by remember(card) { mutableStateOf(initials) }
    val blink = rememberBlink(400L)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(remember { MutableInteractionSource() }, indication = null) { }
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        RetroBlock(
            Modifier.padding(16.dp).widthIn(max = 420.dp).fillMaxWidth(),
            fill = vibe.surface,
            depth = 6.dp,
            contentPadding = PaddingValues(16.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StripedText("RESULTS", androidx.compose.ui.text.TextStyle(fontFamily = Arcade, fontSize = 18.sp), vibe.stripes, blockShadow())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("RANK ", color = vibe.ink, fontFamily = Vcr, fontSize = 26.sp)
                    StripedText(card.rank, androidx.compose.ui.text.TextStyle(fontFamily = Arcade, fontSize = 40.sp), vibe.stripes, blockShadow())
                    Text("   SCORE ", color = vibe.ink, fontFamily = Vcr, fontSize = 26.sp)
                    Text(card.points.toString().padStart(6, '0'), color = vibe.accent, fontFamily = Arcade, fontSize = 14.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Grade.entries.forEach { grade ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(grade.label.uppercase(), color = gradeColor(grade, vibe.isDark), fontFamily = Vcr, fontSize = 18.sp)
                            Text(card.count(grade).toString(), color = vibe.ink, fontFamily = Arcade, fontSize = 12.sp)
                        }
                    }
                }
                ResultLine("best combo", card.bestCombo.toString())
                ResultLine("tight", "${(card.tightness * 100).roundToInt()}% within ${Grade.GREAT.withinMillis.roundToInt()} ms")
                ResultLine("spread", "±${card.all.spreadMillis.roundToInt()} ms")
                if (card.withClick.hits > 0) ResultLine("with the click", spreadLine(card.withClick))
                if (card.alone.hits > 0) ResultLine("on your own", spreadLine(card.alone))
                if (qualifies) {
                    Text(
                        "NEW HI-SCORE! ENTER YOUR INITIALS",
                        color = if (blink) vibe.accent else vibe.ink,
                        fontFamily = Vcr,
                        fontSize = 22.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        repeat(3) { i ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                ArrowKey(up = true, label = "next letter ${i + 1}") { letters = HiScores.turn(letters, i, 1) }
                                Text(letters.getOrElse(i) { 'A' }.toString(), color = vibe.accent, fontFamily = Arcade, fontSize = 22.sp, modifier = Modifier.padding(vertical = 4.dp))
                                ArrowKey(up = false, label = "previous letter ${i + 1}") { letters = HiScores.turn(letters, i, -1) }
                            }
                        }
                    }
                }
                BigButton(if (qualifies) "sign it" else "again!", onClick = { onDone(if (qualifies) letters else null) }, Modifier.padding(top = 8.dp))
            }
        }
    }
}

private fun spreadLine(spread: Spread): String = "${signedMillis(spread.meanMillis).lowercase()} · ${spread.verdict}"

@Composable
private fun ResultLine(label: String, value: String) {
    val vibe = LocalVibe.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), color = vibe.ink.copy(alpha = 0.7f), fontFamily = Vcr, fontSize = 20.sp, modifier = Modifier.weight(1f))
        Text(value.uppercase(), color = vibe.ink, fontFamily = Vcr, fontSize = 20.sp, maxLines = 1)
    }
}

/** A key with a triangle on it, pointing up or down. */
@Composable
private fun ArrowKey(up: Boolean, label: String, onClick: () -> Unit) {
    val vibe = LocalVibe.current
    RetroBlock(
        Modifier.semantics { contentDescription = label },
        onClick = onClick,
        depth = 3.dp,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Canvas(Modifier.size(12.dp)) {
            val path = Path().apply {
                if (up) {
                    moveTo(size.width / 2f, 0f)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                } else {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width / 2f, size.height)
                }
                close()
            }
            drawPath(path, vibe.ink)
        }
    }
}
