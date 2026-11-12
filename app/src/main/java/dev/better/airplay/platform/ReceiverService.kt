package dev.better.airplay.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.better.airplay.App
import dev.better.airplay.R
import dev.better.airplay.domain.model.ReceiverSnapshot
import dev.better.airplay.domain.model.ReceiverStatus
import dev.better.airplay.presentation.home.HomeActivity

/**
 * Always-on host for the AirPlay engine. Platform adapter only: lifecycle + notification;
 * all behaviour lives in domain use cases taken from the shared [dev.better.airplay.di.AppContainer].
 */
class ReceiverService : Service() {

    private val container get() = (application as App).container
    private var running = false
    private var statusSubscription: AutoCloseable? = null
    private var launcherSubscription: AutoCloseable? = null
    private var policySubscription: AutoCloseable? = null
    private var nameSubscription: AutoCloseable? = null
    private var lastNotificationText: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground(notification(getString(R.string.status_starting)))
        container.engine.listener = container.engineEvents
        statusSubscription = container.statusStore.observe(::onStatus)
        launcherSubscription = container.statusStore.observe(PlayerLauncher(this)::onStatus)
        policySubscription = container.statusStore.observe(container.sessionMediaPolicy::onStatus)
        // Follow the TV's own name (Settings → About → Device name) unless the user set a custom one.
        nameSubscription = container.deviceInfo.observeDeviceName {
            if (container.resolveReceiverName() != container.statusStore.snapshot.name) restart()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RESTART -> restart()
            ACTION_DISCONNECT -> if (running) container.engine.disconnectSenders() else start()
            else -> if (!running) start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        nameSubscription?.close()
        policySubscription?.close()
        launcherSubscription?.close()
        statusSubscription?.close()
        if (running) container.stopReceiver()
        running = false
        container.engine.listener = null
        super.onDestroy()
    }

    private fun start() {
        running = true
        container.startReceiver()
    }

    private fun restart() {
        if (running) container.stopReceiver()
        start()
    }

    private fun onStatus(snap: ReceiverSnapshot) {
        val sender = snap.status.currentSender?.name ?: getString(R.string.a_device)
        val text = when (val s = snap.status) {
            ReceiverStatus.Stopped -> getString(R.string.status_stopped)
            ReceiverStatus.Starting -> getString(R.string.status_starting)
            is ReceiverStatus.Ready -> getString(R.string.notif_ready, snap.name)
            is ReceiverStatus.Connecting -> snap.pin?.let { getString(R.string.notif_pin, it) }
                ?: getString(R.string.status_connecting, sender)
            is ReceiverStatus.Mirroring -> getString(R.string.status_mirroring, sender)
            is ReceiverStatus.PlayingVideo -> getString(R.string.status_video, sender)
            is ReceiverStatus.PlayingAudio -> getString(R.string.status_audio, sender)
            is ReceiverStatus.Failed -> getString(R.string.status_error, s.code)
        }
        if (text == lastNotificationText) return   // metadata/size updates don't need a new notification
        lastNotificationText = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, HomeActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val disconnect = PendingIntent.getService(
            this, 1, Intent(this, ReceiverService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_airplay)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.row_disconnect), disconnect).build())
            .build()
    }

    private fun startAsForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    companion object {
        private const val CHANNEL = "receiver"
        private const val NOTIFICATION_ID = 7000
        const val ACTION_DISCONNECT = "dev.better.airplay.DISCONNECT"
        const val ACTION_RESTART = "dev.better.airplay.RESTART"
    }
}
