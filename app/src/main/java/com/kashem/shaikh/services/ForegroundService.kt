package com.kashem.shaikh.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.kashem.shaikh.streaming.CameraManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ForegroundService : LifecycleService() {

    companion object {
        private const val CHANNEL_ID = "kashem_service"
        private const val NOTIFICATION_ID = 1001

        @Volatile private var instance: ForegroundService? = null

        fun getInstance(): ForegroundService? = instance

        fun getCameraManager(): CameraManager? = instance?.cameraManager

        fun upgradeToMediaProjection(): Boolean {
            val svc = instance ?: run {
                AppLogs.warn("upgradeToMediaProjection: instance null")
                return false
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                return svc.upgradeForegroundType()
            }
            var result = false
            val latch = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                result = svc.upgradeForegroundType()
                latch.countDown()
            }
            try { latch.await(3, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
            return result
        }

        fun downgradeFromMediaProjection() {
            val svc = instance ?: return
            if (Looper.myLooper() == Looper.getMainLooper()) {
                svc.downgradeForegroundType()
            } else {
                Handler(Looper.getMainLooper()).post { svc.downgradeForegroundType() }
            }
        }
    }

    // ✅ FIX: @Volatile — background thread থেকে set হবে, main thread পড়বে
    @Volatile private var wsClient: WebSocketClient? = null

    @Volatile var cameraManager: CameraManager? = null
        private set

    override fun onCreate() {
        super.onCreate()

        try { AppLogs.init(applicationContext) } catch (_: Throwable) {}

        instance = this
        AppLogs.logService("onCreate: START")

        try {
            createChannel()
            AppLogs.logService("onCreate: channel OK")
        } catch (e: Throwable) {
            AppLogs.error("onCreate: createChannel FAILED", e)
        }

        try {
            startForegroundSafely()
            AppLogs.logService("onCreate: startForeground OK")
        } catch (e: Throwable) {
            AppLogs.error("onCreate: startForeground FAILED", e)
        }

        try {
            AppLogs.logService("onCreate: CameraManager init START")
            cameraManager = CameraManager(applicationContext, this)
            AppLogs.logService("onCreate: CameraManager init OK")
        } catch (e: Throwable) {
            AppLogs.error("onCreate: CameraManager init FAILED", e)
            cameraManager = null
        }

        // ═══════════════════════════════════════════════
        //  ✅ FIX: WebSocket init — BACKGROUND THREAD
        //  Main thread আর block হবে না
        // ═══════════════════════════════════════════════
        AppLogs.logService("onCreate: WebSocket deferred to bg thread")
        try {
            Thread {
                try {
                    AppLogs.logService("bg: WS init START")
                    val ws = WebSocketClient(applicationContext) { connected ->
                        try {
                            val text = if (connected) "🟢 Connected" else "🔴 Disconnected"
                            updateNotification(text)
                            AppLogs.logWebSocket(if (connected) "CONNECTED" else "DISCONNECTED")
                        } catch (e: Throwable) {
                            AppLogs.error("bg: onConnectionChange failed", e)
                        }
                    }
                    AppLogs.logService("bg: WebSocketClient constructed")
                    wsClient = ws
                    AppLogs.logService("bg: WebSocketClient assigned")
                    ws.connect()
                    AppLogs.logService("bg: connect() returned")
                } catch (e: Throwable) {
                    AppLogs.error("bg: WebSocket init FAILED", e)
                }
            }.apply {
                isDaemon = true
                name = "Kashem-WSInit"
                start()
            }
        } catch (e: Throwable) {
            AppLogs.error("onCreate: failed to spawn WS thread", e)
        }

        AppLogs.logService("onCreate: ✓ COMPLETE (WS deferred)")
    }

    private fun buildFgsType(includeMediaProjection: Boolean): Int {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        } catch (_: Throwable) {}
        if (includeMediaProjection) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        return type
    }

    private fun startForegroundSafely() {
        val notification = buildNotification("Starting...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = buildFgsType(includeMediaProjection = false)
            try {
                startForeground(NOTIFICATION_ID, notification, type)
                AppLogs.info("ForegroundService started with type=$type")
            } catch (e: Throwable) {
                AppLogs.error("startForeground failed (type=$type)", e)
                try {
                    startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    AppLogs.info("dataSync fallback OK")
                } catch (e2: Throwable) {
                    AppLogs.error("dataSync fallback failed", e2)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun upgradeForegroundType(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        AppLogs.info("ForegroundService: upgrading FGS type")
        val notification = buildNotification("🎮 Screen casting active")
        val type = buildFgsType(includeMediaProjection = true)
        return try {
            startForeground(NOTIFICATION_ID, notification, type)
            AppLogs.info("ForegroundService: ✓ upgraded type=$type")
            true
        } catch (e: Throwable) {
            AppLogs.error("upgradeForegroundType failed", e)
            false
        }
    }

    private fun downgradeForegroundType() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        AppLogs.info("ForegroundService: downgrading FGS type")
        val notification = buildNotification("Connected to server")
        val type = buildFgsType(includeMediaProjection = false)
        try {
            startForeground(NOTIFICATION_ID, notification, type)
            AppLogs.info("ForegroundService: downgraded type=$type")
        } catch (e: Throwable) {
            AppLogs.error("downgradeForegroundType failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        AppLogs.logService("onStartCommand")
        return START_STICKY
    }

    override fun onDestroy() {
        AppLogs.logService("onDestroy")
        instance = null
        try { wsClient?.disconnect() } catch (_: Throwable) {}
        try {
            val cam = cameraManager
            if (cam != null) {
                Thread {
                    try { cam.release() } catch (_: Throwable) {}
                }.apply {
                    isDaemon = true
                    name = "CameraCleanup"
                    start()
                }
            }
        } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Kashem Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kashem")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Throwable) {}
    }
}