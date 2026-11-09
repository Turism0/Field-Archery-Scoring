package uk.co.fnurble.fieldarchery.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import uk.co.fnurble.fieldarchery.FieldArcheryApp
import uk.co.fnurble.fieldarchery.MainActivity
import uk.co.fnurble.fieldarchery.R

/**
 * Keeps live groups going in the background (app 2.4, Pete): phone to phone and the server keep in step while the app
 * is in the background or the screen is off, from making / joining a live group until it's closed. Android needs a
 * notification while it runs; its Stop button stops it until the group is opened in the app again. Starts again after
 * the phone restarts if a group is still going.
 */
class LiveGroupService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Live groups", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a live group is sharing scores in the background"
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val live = (application as FieldArcheryApp).container.liveGroupRepository
        if (intent?.action == ACTION_STOP) {
            live.pauseBackground()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification("Keeping scores in step with the other phones."),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
        } catch (e: Exception) {
            // Not allowed just now (e.g. started from the background): the app carries on while it's open.
            Log.w(TAG, "Couldn't start in the foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            // The CPU stays awake with the screen off so the phones keep talking (released when the group ends).
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FieldArchery:liveGroup")
                .apply { acquire(MAX_HOURS * 60 * 60 * 1000L) }
        }
        if (job == null) {
            job = scope.launch {
                live.keepRunning { text -> notify(text) }
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun notify(text: String) {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text)) }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, // Like the launcher icon: back to the app as it was.
            Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LiveGroupService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify_live)
            .setContentTitle("Live group")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "LiveGroupService"
        private const val CHANNEL = "live_groups"
        private const val NOTIFICATION_ID = 2401
        private const val ACTION_STOP = "uk.co.fnurble.fieldarchery.STOP_LIVE"
        /** Safety limit on keeping the CPU awake (the service itself runs until the group closes). */
        private const val MAX_HOURS = 12L

        /** Starts (or nudges) the service; safe to call often. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, LiveGroupService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't start the live group service", e)
            }
        }
    }
}

/** After the phone restarts: carry on with a live group that was still going (2.4). */
class LiveGroupBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? FieldArcheryApp ?: return
        if (app.container.liveGroupRepository.backgroundGroupIds().isNotEmpty()) LiveGroupService.start(context)
    }
}
