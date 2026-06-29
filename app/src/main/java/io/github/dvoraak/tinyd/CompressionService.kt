package io.github.dvoraak.tinyd

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Lifetime-pinning foreground service for a batch transcode.
 *
 * This service deliberately does NOT own the Media3 Transformer — that
 * stays in [CompressorViewModel] where it already works. The service's
 * only job is to pin the process to a foregroundService lifecycle so
 * Android won't suspend it under Doze / app-standby when the user locks
 * the screen or switches apps mid-batch.
 *
 * It collects [CompressionStore.state], derives the notification state
 * fresh on every UI-state change, and self-terminates when the derived
 * `active` flag flips false. The Cancel action button on the notification
 * feeds into [CompressionEvents.cancelRequested], which the ViewModel
 * observes and reacts to by calling cancelCompression().
 */
class CompressionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collector: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                // Notification cancel button → relay to the ViewModel via the
                // event bus. The ViewModel listens and calls cancelCompression().
                CompressionEvents.cancelRequested.tryEmit(Unit)
                // Don't stop here — the ViewModel will flip notificationState.active=false
                // when it actually finishes tearing down, and the collector handles
                // the stop transition cleanly.
                return START_NOT_STICKY
            }
        }

        // Promote to foreground immediately (within the 5s grace window) using
        // a fresh derivation of the state singleton — reading CompressionStore
        // directly avoids the one-frame race the previous separate
        // CompressionEvents.notificationState buffer introduced, which is what
        // made the i/N batch counter sometimes appear blank on first show.
        val initialUi = CompressionStore.state.value
        val initial = deriveNotificationState(initialUi, this)
        val notif = buildNotification(initial)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            )
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }

        if (collector == null) {
            collector = scope.launch {
                CompressionStore.state.collect { uiState ->
                    val notifState = deriveNotificationState(uiState, this@CompressionService)
                    if (notifState.active) {
                        notificationManager().notify(NOTIFICATION_ID, buildNotification(notifState))
                    } else {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        collector?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(state: CompressionEvents.NotificationState): android.app.Notification {
        // Tapping the notification body opens (or brings to front) the activity.
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelPending = PendingIntent.getService(
            this, 1,
            Intent(this, CompressionService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(state.text.ifBlank { getString(R.string.notif_default_text) })
            // SubText shows in the collapsed view AND the expanded one, so
            // putting the "1 / 3" counter here makes it visible without
            // requiring the user to swipe the notification down.
            .setSubText(state.subText.takeIf { it.isNotBlank() })
            .setStyle(NotificationCompat.BigTextStyle().bigText(state.text))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openPending)
            .addAction(0, getString(R.string.cancel), cancelPending)

        when {
            state.progressPercent in 0..100 -> builder.setProgress(100, state.progressPercent, false)
            state.progressPercent == CompressionEvents.NotificationState.NO_PROGRESS -> {
                // Deliberately do NOT call setProgress at all. The documented
                // setProgress(0, 0, false) "removes the bar" but in practice
                // some Android skins still render a residual spinner from the
                // previous update — skipping the call entirely produces a
                // clean bar-less notification on every device we tested.
            }
            else -> {
                // -1 = codec init / inter-item gap. Indeterminate bar
                // reads as "working but no concrete number yet".
                builder.setProgress(100, 0, true)
            }
        }

        return builder.build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = notificationManager()
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_description)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        mgr.createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager = getSystemService()!!

    companion object {
        private const val CHANNEL_ID = "tinyd.compression"
        private const val NOTIFICATION_ID = 1001
        const val NOTIFICATION_ID_DONE = 1002 // retained for the activity's cancel-on-finalize cleanup
        private const val ACTION_CANCEL = "io.github.dvoraak.tinyd.action.CANCEL"

        /** Idempotent — calling repeatedly just refreshes the existing service's notification. */
        fun ensureRunning(context: Context) {
            val intent = Intent(context, CompressionService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}
