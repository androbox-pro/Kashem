package com.kashem.shaikh.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // ✅ FIX: Boot path-এ AppLogs init — নাহলে log silently drop হয়
        AppLogs.init(context)

        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON") {

            AppLogs.logService("BootReceiver: BOOT_COMPLETED received")

            try {
                val serviceIntent = Intent(context, ForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                AppLogs.logService("BootReceiver: ForegroundService started")
            } catch (e: Exception) {
                AppLogs.error("BootReceiver: failed to start service", e)
            }
        }
    }
}