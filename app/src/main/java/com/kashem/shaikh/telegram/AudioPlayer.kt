package com.kashem.shaikh.telegram

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log

object AudioPlayer {
    private const val TAG = "AudioPlayer"
    private var mediaPlayer: MediaPlayer? = null

    fun playUrl(context: Context, url: String, onComplete: (() -> Unit)? = null) {
        stop()
        if (url.isBlank()) {
            Log.e(TAG, "Empty URL")
            return
        }
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, Uri.parse(url))
                prepareAsync()
                setOnPreparedListener {
                    start()
                    Log.d(TAG, "Playing: $url")
                }
                setOnCompletionListener {
                    onComplete?.invoke()
                    Log.d(TAG, "Playback completed")
                    releasePlayer()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    releasePlayer()
                    true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "playUrl exception: ${e.message}")
            releasePlayer()
        }
    }

    fun stop() {
        releasePlayer()
    }

    private fun releasePlayer() {
        mediaPlayer?.apply {
            if (isPlaying) stop()
            release()
        }
        mediaPlayer = null
    }
}