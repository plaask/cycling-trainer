package io.github.cyclingtrainer.app.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.cyclingtrainer.app.MainActivity
import io.github.cyclingtrainer.app.R

/**
 * Keeps a live ride's process out of the cached bucket, so the 1 Hz recording
 * and the BLE link survive the screen going off.
 *
 * This is the platform's own answer to "keep my session alive", and the only
 * one this app wants to use:
 *
 *  - a real foreground service with a **user-visible notification**, started
 *    while the activity is visible (Android 12+ forbids starting one from the
 *    background anyway — the ride is started by the athlete, on screen),
 *  - typed `connectedDevice`, which is the type Android 14+ requires for a
 *    session that talks to a Bluetooth peripheral,
 *  - `START_NOT_STICKY`: nothing here resurrects itself. No alarm heartbeats,
 *    no second process, no silently-restarted service. When the ride ends the
 *    service ends, and the notification goes with it.
 *
 * The session objects themselves stay in [io.github.cyclingtrainer.app.AppViewModel]
 * and are not moved here: a service does not need to own the work in order to
 * keep the process unfrozen, and moving the BLE stack across a process
 * boundary would have been a much larger change for the same guarantee. What
 * the service does need is the ride's state (for the notification) and the
 * ability to reach the session's controls (pause / stop), which the activity
 * already hosts as a broadcast receiver.
 */
class RideSessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        // Since Android 14 a foreground service must declare a type, and
        // connectedDevice additionally requires BLUETOOTH_CONNECT / SCAN /
        // ADVERTISE to have been granted. If the athlete denied Bluetooth,
        // startForeground throws — that must not take the ride down with it,
        // so the service gives up and the session simply runs without the
        // screen-off guarantee.
        val started = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(this, stateOf(intent)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        }.isSuccess
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        lastTexts = null
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 4711
        const val CHANNEL_ID = "ride_session"

        /** Pause/resume, from both the PiP menu and the notification. */
        const val ACTION_TOGGLE_PAUSE = "io.github.cyclingtrainer.app.RIDE_TOGGLE_PAUSE"

        /** Stop the ride, from the notification. */
        const val ACTION_STOP = "io.github.cyclingtrainer.app.RIDE_STOP"

        private const val EXTRA_PHASE = "phase"
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_ELAPSED = "elapsed"
        private const val EXTRA_TOTAL = "total"

        private const val REQUEST_OPEN = 10
        private const val REQUEST_TOGGLE = 11
        private const val REQUEST_STOP = 12

        /** True between a successful startForeground and service teardown. */
        @Volatile
        private var running = false

        /** Last rendered (title, text); re-rendering it every second is waste. */
        @Volatile
        private var lastTexts: Pair<String, String>? = null

        /**
         * Starts (or re-arms) the service with the ride's current state. Safe to
         * call on every tick: [update] is the one that carries the state.
         */
        fun start(context: Context, state: RideNotificationState) {
            ensureChannel(context)
            val intent = Intent(context, RideSessionService::class.java)
                .putExtra(EXTRA_PHASE, state.phase.name)
                .putExtra(EXTRA_TARGET, state.targetWatts ?: -1)
                .putExtra(EXTRA_ELAPSED, state.elapsedSec)
                .putExtra(EXTRA_TOTAL, state.totalSec)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        /** Mirrors the ride into the notification; no-op once the text repeats. */
        fun update(context: Context, state: RideNotificationState) {
            if (!running) return
            val texts = RideNotificationText.title(state) to RideNotificationText.text(state)
            if (texts == lastTexts) return
            lastTexts = texts
            if (!canPostNotifications(context)) return
            runCatching {
                NotificationManagerCompat.from(context)
                    .notify(NOTIFICATION_ID, notification(context, state))
            }
        }

        fun stop(context: Context) {
            running = false
            lastTexts = null
            runCatching { context.stopService(Intent(context, RideSessionService::class.java)) }
        }

        private fun canPostNotifications(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

        private fun stateOf(intent: Intent?): RideNotificationState {
            val phase = runCatching {
                SessionEngine.Phase.valueOf(intent?.getStringExtra(EXTRA_PHASE) ?: "")
            }.getOrDefault(SessionEngine.Phase.RUNNING)
            val target = intent?.getIntExtra(EXTRA_TARGET, -1) ?: -1
            return RideNotificationState(
                phase = phase,
                targetWatts = target.takeIf { it >= 0 },
                elapsedSec = intent?.getIntExtra(EXTRA_ELAPSED, 0) ?: 0,
                totalSec = intent?.getIntExtra(EXTRA_TOTAL, 0) ?: 0,
            )
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "训练会话",
                    // Silent and low: this is a status display, not an alert.
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "训练进行中的常驻通知：目标功率与计时"
                    setShowBadge(false)
                },
            )
        }

        private fun notification(context: Context, state: RideNotificationState): Notification {
            val open = PendingIntent.getActivity(
                context,
                REQUEST_OPEN,
                Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_LAUNCHER)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val paused = state.phase == SessionEngine.Phase.PAUSED
            val toggle = PendingIntent.getBroadcast(
                context,
                REQUEST_TOGGLE,
                Intent(ACTION_TOGGLE_PAUSE).setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val stop = PendingIntent.getBroadcast(
                context,
                REQUEST_STOP,
                Intent(ACTION_STOP).setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_fitness_center)
                .setContentTitle(RideNotificationText.title(state))
                .setContentText(RideNotificationText.text(state))
                .setContentIntent(open)
                // Ongoing while the ride is: it is the visible half of the
                // foreground-service contract, and it leaves with the ride.
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(
                    if (paused) R.drawable.ic_pip_play else R.drawable.ic_pip_pause,
                    if (paused) "继续" else "暂停",
                    toggle,
                )
                .addAction(R.drawable.ic_stop, "停止", stop)
                .build()
        }
    }
}
