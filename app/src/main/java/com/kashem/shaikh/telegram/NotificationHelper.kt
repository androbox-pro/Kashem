package com.kashem.shaikh.telegram

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

object NotificationHelper {
    private const val CHANNEL_ID = "remote_commands_channel"
    private const val NOTIFICATION_ID = 999

    fun showNotification(context: Context, title: String, message: String, autoCancel: Boolean = true) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Remote Commands",
                NotificationManager.IMPORTANCE_HIGH
            )
            manager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_info_details) // আরও ইউনিভার্সাল আইকন
            .setAutoCancel(autoCancel)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}