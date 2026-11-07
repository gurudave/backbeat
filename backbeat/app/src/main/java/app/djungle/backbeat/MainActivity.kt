package app.djungle.backbeat

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
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
        // Keys from a pedal or a keyboard are taken before the on-screen keyboard sees them: it
        // otherwise keeps keys such as the space bar for itself, even with nothing to type into.
        // That only reaches a view on the way to the focus, so this one keeps the focus whenever
        // nothing inside it (a tape's label, being written) has taken it.
        val keys = object : FrameLayout(this) {
            override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean =
                Pedal.dispatch(event) || super.dispatchKeyEventPreIme(event)
        }
        keys.isFocusableInTouchMode = true
        if (Build.VERSION.SDK_INT >= 26) keys.defaultFocusHighlightEnabled = false
        keys.addView(ComposeView(this).apply { setContent { BackbeatApp() } })
        setContentView(keys)
        keys.viewTreeObserver.addOnGlobalFocusChangeListener { _, now -> if (now == null) keys.requestFocus() }
        this.keys = keys
    }

    private var keys: FrameLayout? = null

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && currentFocus == null) keys?.requestFocus()
    }

    /** A pedal, a headset's button or a keyboard, if nothing on the screen has focus: see [Pedal]. */
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
