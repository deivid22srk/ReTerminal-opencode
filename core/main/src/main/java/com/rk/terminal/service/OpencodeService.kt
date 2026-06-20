package com.rk.terminal.service

import android.app.Notification
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
import com.rk.opencode.OpencodeManager
import com.rk.opencode.OpencodeServer
import com.rk.resources.drawables
import com.rk.terminal.ui.activities.terminal.MainActivity

/**
 * Foreground service that owns the opencode serve process lifecycle.
 *
 * Why a foreground service:
 *  - On Android 8+, a background app process can be killed at any time. The user wants the
 *    localhost server to keep running even when the app is backgrounded.
 *  - Starting a foreground service keeps the process priority high enough to survive.
 *  - It also gives the user a persistent notification, which is the Android-friendly way to
 *    expose "an ongoing background operation" to the user.
 *
 * Lifecycle:
 *  - [ACTION_START]: promotes to foreground, calls [OpencodeServer.launchProcess].
 *  - [ACTION_STOP]:  calls [OpencodeServer.stopInternal], then stopSelf().
 *
 * The service does NOT bind from the UI; the UI talks directly to [OpencodeServer] for
 * state/logs, and only sends Intents here to start/stop the process.
 */
class OpencodeService : Service() {

    companion object {
        const val ACTION_START = "com.rk.opencode.action.START"
        const val ACTION_STOP = "com.rk.opencode.action.STOP"
        const val EXTRA_HOSTNAME = "extra.hostname"
        const val EXTRA_PORT = "extra.port"

        private const val CHANNEL_ID = "opencode_server_channel"
        private const val NOTIF_ID = 4242
    }

    private val notificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        }
        // Promote to foreground immediately so the process has time to launch.
        startForegroundCompat(buildNotification("Iniciando servidor opencode…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val hostname = intent.getStringExtra(EXTRA_HOSTNAME)
                    ?: OpencodeManager.DEFAULT_HOSTNAME
                val port = intent.getIntExtra(EXTRA_PORT, OpencodeManager.DEFAULT_PORT)
                OpencodeServer.launchProcess(hostname, port)
                // Update notification text.
                notificationManager.notify(
                    NOTIF_ID,
                    buildNotification("Servidor opencode rodando em http://$hostname:$port")
                )
            }
            ACTION_STOP -> {
                OpencodeServer.stopInternal()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        // If the system kills the service, don't restart automatically — the user must
        // explicitly start it again. Otherwise a crashed opencode would be respawned
        // in a loop.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // Best-effort cleanup if the service is destroyed out from under us.
        OpencodeServer.stopInternal()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            // MainActivity reads this to know it should deep-link to the opencode screen.
            putExtra("opencode_screen", true)
        }
        val pi = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = Intent(this, OpencodeService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ReTerminal · opencode")
            .setContentText(contentText)
            .setSmallIcon(drawables.terminal)
            .setContentIntent(pi)
            .addAction(NotificationCompat.Action.Builder(null, "Parar", stopPi).build())
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OpenCode Server",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Notificação do servidor opencode em execução"
        }
        notificationManager.createNotificationChannel(channel)
    }
}
