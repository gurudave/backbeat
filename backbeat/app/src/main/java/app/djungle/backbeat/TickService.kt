package app.djungle.backbeat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.djungle.backbeat.engine.EngineState
import app.djungle.backbeat.engine.Metronome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the metronome going with the screen off or in another app, when "keep ticking" is on.
 * Android only lets sound carry on in the background from a foreground service, which must
 * show a notification; this one says the tempo and has a STOP button. It ends itself as soon
 * as the metronome stops, however it was stopped.
 */
class TickService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always go into the foreground first: Android insists, even when about to stop.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(Metronome.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        if (intent?.action == ACTION_STOP) Metronome.stop()
        if (watcher == null) {
            watcher = scope.launch {
                Metronome.state.collect { state ->
                    if (state.playing) {
                        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(state))
                    } else {
                        ServiceCompat.stopForeground(this@TickService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(state: EngineState): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, TickService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val tempo = state.bpm?.let { "$it BPM" } ?: "Ready"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tick)
            .setContentTitle("Backbeat · $tempo")
            .setContentText("Ticking. Tap to open, or stop it here.")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "ticking"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "app.djungle.backbeat.STOP"

        /** Call once the metronome is playing. */
        fun start(context: Context) {
            ensureChannel(context)
            runCatching { ContextCompat.startForegroundService(context, Intent(context, TickService::class.java)) }
        }

        fun end(context: Context) {
            context.stopService(Intent(context, TickService::class.java))
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Ticking in the background", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Backbeat keeps time with the screen off."
                    setShowBadge(false)
                }
            )
        }
    }
}
