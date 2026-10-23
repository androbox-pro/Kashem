package com.kashem.shaikh.services

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.kashem.shaikh.streaming.DeviceController
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WebSocketClient(
    private val context: Context,
    private val onConnectionChange: (Boolean) -> Unit = {}
) {

    companion object {
        private const val TAG = "WebSocketClient"
        private const val RECONNECT_BASE_MS = 5_000L
        private const val RECONNECT_MAX_MS = 300_000L
        private const val HEALTH_CHECK_INTERVAL_MS = 30_000L
        private const val HEALTH_CHECK_STALE_MS = 90_000L

        @Volatile private var instance: WebSocketClient? = null

        fun sendRaw(json: String): Boolean {
            return try { instance?.sendRaw(json) ?: false } catch (_: Throwable) { false }
        }

        private fun safeLog(msg: String) {
            try { AppLogs.logService(msg) }
            catch (_: Throwable) { Log.i("WS", msg) }
        }

        private fun safeErr(msg: String, t: Throwable? = null) {
            try { AppLogs.error(msg, t) }
            catch (_: Throwable) { Log.e("WS", msg, t) }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  ✅ FIX: OkHttpClient now LAZY — constructor-এ তৈরি হবে না
    //  SIGSEGV এড়াতে first access হবে connect()-এর try-catch-এ
    // ═══════════════════════════════════════════════════════════
    private val client: OkHttpClient by lazy {
        safeLog("WS: LAZY-init OkHttpClient START")
        try {
            val builder = OkHttpClient.Builder()
            safeLog("WS: LAZY-init builder created")

            val configured = builder
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
            safeLog("WS: LAZY-init builder configured")

            val c = configured.build()
            safeLog("WS: LAZY-init OkHttpClient BUILT ✓")
            c
        } catch (e: Throwable) {
            safeErr("WS: LAZY-init OkHttpClient FAILED", e)
            throw e
        }
    }

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var isConnected = false
    @Volatile private var shouldReconnect = true
    @Volatile private var reconnectAttempts = 0
    private var reconnectThread: Thread? = null
    private var healthCheckThread: Thread? = null
    @Volatile private var lastMessageTime: Long = 0L

    private val deviceId: String by lazy {
        try {
            val id = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            )
            if (id.isNullOrBlank()) "unknown" else id
        } catch (e: Throwable) {
            Log.e(TAG, "deviceId read failed", e)
            try { AppLogs.warn("WS: deviceId read failed: ${e.message}") } catch (_: Throwable) {}
            "unknown"
        }
    }

    init {
        safeLog("WS: constructor START")
        try {
            instance = this
            safeLog("WS: instance assigned")
        } catch (e: Throwable) {
            safeErr("WS: instance assign failed", e)
        }
        safeLog("WS: constructor DONE")
    }

    fun sendRaw(json: String): Boolean {
        return try {
            val ws = webSocket
            if (ws != null && isConnected) ws.send(json) else false
        } catch (_: Throwable) { false }
    }

    fun connect() {
        safeLog("WS: connect() START")
        try {
            // ── Step 1: Config ──
            safeLog("WS: step 1 - loading config")
            val config = ConfigManager.getConfig(context)
            if (config == null) {
                safeErr("WS: No config")
                try { onConnectionChange(false) } catch (_: Throwable) {}
                scheduleReconnect()
                return
            }
            safeLog("WS: step 2 - config loaded (socket=${config.socket})")

            try { webSocket?.cancel() } catch (_: Throwable) {}
            webSocket = null
            shouldReconnect = true

            // ── Step 3: deviceId ──
            val id = deviceId
            safeLog("WS: step 3 - deviceId=$id, model=${Build.MODEL}")

            // ── Step 4: Build request ──
            safeLog("WS: step 4 - building request")
            val request = try {
                Request.Builder()
                    .url(config.socket)
                    .addHeader("device-id", id)
                    .addHeader("model", Build.MODEL ?: "unknown")
                    .addHeader("username", config.username ?: "")
                    .addHeader("version", Build.VERSION.RELEASE ?: "unknown")
                    .build()
            } catch (e: Throwable) {
                safeErr("WS: request build failed", e)
                return
            }
            safeLog("WS: step 5 - request built")

            // ── Step 6: Trigger OkHttpClient (LAZY) ──
            safeLog("WS: step 6 - accessing OkHttpClient (lazy trigger)")
            val okClient = client
            safeLog("WS: step 7 - OkHttpClient ready")

            // ── Step 8: WebSocket ──
            safeLog("WS: step 8 - calling newWebSocket()")
            webSocket = okClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    try {
                        isConnected = true
                        reconnectAttempts = 0
                        lastMessageTime = System.currentTimeMillis()
                        onConnectionChange(true)
                        AppLogs.logWebSocket("OPEN")
                        startHealthCheck()
                    } catch (e: Throwable) { Log.e(TAG, "onOpen failed", e) }
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    try {
                        lastMessageTime = System.currentTimeMillis()
                        AppLogs.debug("WS ← $text")
                        handleCommand(text)
                    } catch (e: Throwable) { Log.e(TAG, "onMessage failed", e) }
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    try {
                        AppLogs.warn("WS CLOSING: $code $reason")
                        isConnected = false
                        onConnectionChange(false)
                        try { ws.close(1000, null) } catch (_: Throwable) {}
                    } catch (_: Throwable) {}
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    try {
                        AppLogs.warn("WS CLOSED: $code $reason")
                        isConnected = false
                        onConnectionChange(false)
                        stopHealthCheck()
                        scheduleReconnect()
                    } catch (_: Throwable) {}
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    try {
                        val code = response?.code ?: -1
                        safeErr("WS FAILURE (http=$code): ${t.message}", t)
                        isConnected = false
                        onConnectionChange(false)
                        stopHealthCheck()
                        scheduleReconnect()
                    } catch (_: Throwable) {}
                }
            })
            safeLog("WS: step 9 - newWebSocket returned OK")
        } catch (e: Throwable) {
            safeErr("WS: connect() OUTER FAILED", e)
            scheduleReconnect()
        }
    }

    private fun handleCommand(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "")
            if (type == "input") {
                Thread {
                    try {
                        val result = DeviceController.handleInput(json)
                        AppLogs.debug("Input handled: $result")
                    } catch (e: Throwable) {
                        AppLogs.error("Input handling failed", e)
                    }
                }.start()
                return
            }

            val cmd = json.optString("cmd", "")
            val reqId = json.optString("reqId", "")
            val paramsObj = json.optJSONObject("params")
            val params = mutableMapOf<String, String>()
            paramsObj?.keys()?.forEach { key ->
                params[key] = paramsObj.optString(key, "")
            }

            AppLogs.logCommandReceived(cmd, params)

            Thread {
                try {
                    val result = CommandProcessor.processCommand(cmd, params, context)
                    AppLogs.logCommandResult(cmd, result)
                    sendResultSafely(cmd, result, reqId)
                } catch (e: Throwable) {
                    AppLogs.error("Command execution failed: $cmd", e)
                    sendResultSafely(cmd, "ERROR: ${e.message}", reqId)
                }
            }.start()
        } catch (e: Throwable) {
            Log.e(TAG, "handleCommand failed", e)
        }
    }

    private fun sendResultSafely(cmd: String, result: String, reqId: String = "") {
        try {
            val ws = webSocket
            if (ws != null && isConnected) {
                val resultJson = JSONObject().apply {
                    put("type", "result")
                    put("cmd", cmd)
                    put("result", result)
                    if (reqId.isNotEmpty()) put("reqId", reqId)
                }
                ws.send(resultJson.toString())
            }
        } catch (e: Throwable) {
            Log.e(TAG, "sendResult failed", e)
        }
    }

    private fun startHealthCheck() {
        stopHealthCheck()
        healthCheckThread = Thread {
            try {
                while (shouldReconnect && isConnected) {
                    try { Thread.sleep(HEALTH_CHECK_INTERVAL_MS) } catch (_: InterruptedException) { break }
                    if (!isConnected || !shouldReconnect) break
                    val age = getLastMessageAge()
                    if (age > HEALTH_CHECK_STALE_MS) {
                        AppLogs.warn("Health check: stale ${age / 1000}s")
                        try { webSocket?.cancel() } catch (_: Throwable) {}
                        break
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "health check failed", e)
            }
        }.apply {
            isDaemon = true
            name = "WS-HealthCheck"
            start()
        }
    }

    private fun stopHealthCheck() {
        try { healthCheckThread?.interrupt() } catch (_: Throwable) {}
        healthCheckThread = null
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        try { reconnectThread?.interrupt() } catch (_: Throwable) {}
        reconnectThread = null

        val delayMs = minOf(
            RECONNECT_BASE_MS * (1L shl minOf(reconnectAttempts, 4)),
            RECONNECT_MAX_MS
        )
        reconnectAttempts++
        safeLog("WS: Reconnect in ${delayMs / 1000}s (attempt $reconnectAttempts)")

        reconnectThread = Thread {
            try {
                Thread.sleep(delayMs)
                if (shouldReconnect) connect()
            } catch (_: InterruptedException) {}
            catch (e: Throwable) { Log.e(TAG, "reconnect failed", e) }
        }.apply {
            isDaemon = true
            name = "WS-Reconnect"
            start()
        }
    }

    fun disconnect() {
        shouldReconnect = false
        try { reconnectThread?.interrupt() } catch (_: Throwable) {}
        reconnectThread = null
        stopHealthCheck()
        reconnectAttempts = 0
        try { webSocket?.close(1000, "Manual disconnect") } catch (_: Throwable) {}
        webSocket = null
        isConnected = false
        safeLog("WS: Manual disconnect")
    }

    fun isConnected(): Boolean = isConnected

    fun getLastMessageAge(): Long {
        if (lastMessageTime == 0L) return -1
        return System.currentTimeMillis() - lastMessageTime
    }
}