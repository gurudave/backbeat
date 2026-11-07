package app.djungle.backbeat

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.djungle.backbeat.data.AppSettings
import app.djungle.backbeat.data.Logbook
import app.djungle.backbeat.engine.Ear
import app.djungle.backbeat.engine.Metronome
import app.djungle.backbeat.ui.BackbeatApp
import app.djungle.backbeat.ui.Pedal

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Metronome.prepare()
        val app = applicationContext
        // Every run of the metronome goes in the logbook, however it was stopped.
        Metronome.sessionEnded = { millis -> Logbook.add(app, millis) }
        Ear.remember(AppSettings.calibration(app))
        Ear.nudgeMillis = AppSettings.nudge(app)
        setContent { BackbeatApp() }
    }

    /** A pedal, a headset's button or a keyboard: see [Pedal]. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = Pedal.dispatch(event) || super.dispatchKeyEvent(event)

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) return
        // The Ear only ever listens with the app on the screen.
        Ear.stop()
        // Like a tape deck with the lid shut: leaving the app stops the click, unless
        // "keep ticking" is on. Turning the phone round is not leaving.
        if (!AppSettings.keepTicking(this)) Metronome.stop()
    }
}
