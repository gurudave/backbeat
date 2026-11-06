package app.djungle.backbeat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.djungle.backbeat.data.AppSettings
import app.djungle.backbeat.engine.Metronome
import app.djungle.backbeat.ui.BackbeatApp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BackbeatApp() }
    }

    override fun onStop() {
        super.onStop()
        // Like a tape deck with the lid shut: leaving the app stops the click, unless
        // "keep ticking" is on. Turning the phone round is not leaving.
        if (!isChangingConfigurations && !AppSettings.keepTicking(this)) Metronome.stop()
    }
}
