package com.poringpet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/** Publishes the pet as an ongoing notification for OEM Dynamic Bar integrations. */
class SystemDynamicBarService : Service() {
    companion object {
        const val ACTION_STOP = "com.poringpet.action.STOP_SYSTEM_DYNAMIC_BAR"
        private const val CHANNEL = "poring-system-dynamic-bar"
        private const val NOTIFICATION_ID = 9

        @Volatile var running = false
            private set

        fun updatePetState(context: Context, state: String) {
            val safeState = state.takeIf { it in setOf("Looking around", "Following", "Happy", "Sleeping", "Running", "Music", "Charging") } ?: "Looking around"
            context.getSharedPreferences("poring_dynamic_bar", Context.MODE_PRIVATE)
                .edit().putString("live_action", safeState).apply()
            if (running) context.getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(context, safeState))
        }

        private fun buildNotification(context: Context, state: String): Notification {
            val openApp = PendingIntent.getActivity(
                context, 9, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val stop = PendingIntent.getService(
                context, 10, Intent(context, SystemDynamicBarService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL)
                else Notification.Builder(context)
            builder.setSmallIcon(R.drawable.ic_poring_tile)
                .setContentTitle("Poring Pet")
                .setContentText("With you · $state")
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(openApp)
                .addAction(0, "Stop", stop)
            builder.setStyle(Notification.BigTextStyle().bigText("Your pet is here · $state"))
            return builder.build()
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, "Poring Pet in Dynamic Bar", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Ongoing pet status for phone Dynamic Bar integrations"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        running = true
        startForeground(NOTIFICATION_ID, buildNotification(
            this,
            getSharedPreferences("poring_dynamic_bar", MODE_PRIVATE).getString("live_action", "Looking around") ?: "Looking around"
        ))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
