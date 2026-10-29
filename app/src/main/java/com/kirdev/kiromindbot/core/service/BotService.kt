package com.kirdev.kiromindbot.core.service

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
import com.kirdev.kiromindbot.MainActivity
import com.kirdev.kiromindbot.core.di.AppGraph

/**
 * Keeps the process alive while the bot is switched on, with a visible notification.
 * Started only by the user's power button; stops the poller when destroyed.
 */
class BotService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground must come first: Android kills a started FGS that skips it.
        val notification = notification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, notification)
        }
        val graph = AppGraph.get(this)
        if (!graph.session.running) {
            stopSelf()
            return START_NOT_STICKY
        }
        graph.telegram.start()
        // Not sticky: after a crash the bot stays off until the user turns it on again.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        AppGraph.get(this).telegram.stop()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Работа бота", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(com.kirdev.kiromindbot.R.drawable.ic_stat_kiromind)
            .setContentTitle("KiroMind Bot работает")
            .setContentText("Отвечает в Telegram. Остановить — кнопкой на главном экране.")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "bot_running"
        private const val ALERTS = "bot_alerts"
        private const val ALERT_ID = 2

        /** One replaceable alert; [summary] is a status line, never message text or a token. */
        fun alert(context: Context, summary: String) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (!manager.areNotificationsEnabled()) return
            manager.createNotificationChannel(NotificationChannel(ALERTS, "Ошибки бота", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            manager.notify(ALERT_ID, Notification.Builder(context, ALERTS)
                .setSmallIcon(com.kirdev.kiromindbot.R.drawable.ic_stat_kiromind)
                .setContentTitle("KiroMind Bot: нет связи с Telegram")
                .setContentText(summary)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
        }
        private const val ID = 1

        fun start(context: Context) = context.startForegroundService(Intent(context, BotService::class.java))
        fun stop(context: Context) { context.stopService(Intent(context, BotService::class.java)) }
    }
}
