package com.kashem.shaikh.actions

import android.content.Context
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.kashem.shaikh.services.AppLogs

object DeviceAlert {

    @Volatile private var ringtone: Ringtone? = null
    private val handler = Handler(Looper.getMainLooper())

    /**
     * ডিভাইস বাজায় (alarm tone) নির্দিষ্ট সময়ের জন্য
     */
    fun ring(context: Context, seconds: Int = 10): String {
        stopRing()
        val safeSeconds = seconds.coerceIn(1, 300)

        return try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: return "ERROR: No default ringtone found"

            val r = RingtoneManager.getRingtone(context, uri)
                ?: return "ERROR: Cannot get ringtone"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                r.isLooping = true
            }
            r.play()
            ringtone = r

            AppLogs.info("DeviceAlert: ringing for ${safeSeconds}s")
            handler.postDelayed({ stopRing() }, safeSeconds * 1000L)

            "SUCCESS: Ringing for ${safeSeconds}s"
        } catch (e: Exception) {
            AppLogs.error("DeviceAlert.ring failed", e)
            "ERROR: ${e.message}"
        }
    }

    /**
     * Ringing বন্ধ করে
     */
    fun stopRing(): String {
        return try {
            ringtone?.let {
                if (it.isPlaying) it.stop()
            }
            ringtone = null
            "SUCCESS: Ring stopped"
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    /**
     * Vibrate করে নির্দিষ্ট সময়ের জন্য
     */
    fun vibrate(context: Context, millis: Long = 2000L): String {
        val safeMs = millis.coerceIn(100L, 60_000L)

        return try {
            val vib: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                        as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(
                    VibrationEffect.createOneShot(
                        safeMs,
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(safeMs)
            }

            AppLogs.info("DeviceAlert: vibrate ${safeMs}ms")
            "SUCCESS: Vibrated ${safeMs}ms"
        } catch (e: Exception) {
            AppLogs.error("DeviceAlert.vibrate failed", e)
            "ERROR: ${e.message}"
        }
    }

    /**
     * Pattern vibration (SOS-এর মতো)
     */
    fun vibratePattern(context: Context): String {
        return try {
            val vib: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                        as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            val pattern = longArrayOf(0, 500, 300, 500, 300, 500, 500, 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(pattern, -1)
            }
            "SUCCESS: Pattern vibration started"
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    /**
     * সব stream এর volume max করে
     */
    fun maxVolume(context: Context): String {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            am.setStreamVolume(
                AudioManager.STREAM_ALARM,
                am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0
            )
            am.setStreamVolume(
                AudioManager.STREAM_RING,
                am.getStreamMaxVolume(AudioManager.STREAM_RING), 0
            )
            am.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0
            )
            am.setStreamVolume(
                AudioManager.STREAM_NOTIFICATION,
                am.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION), 0
            )

            AppLogs.info("DeviceAlert: volume maxed (all streams)")
            "SUCCESS: All volumes maxed"
        } catch (e: Exception) {
            AppLogs.error("DeviceAlert.maxVolume failed", e)
            "ERROR: ${e.message}"
        }
    }

    /**
     * Silent mode (ringer off)
     */
    fun silentMode(context: Context): String {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.ringerMode = AudioManager.RINGER_MODE_SILENT
            "SUCCESS: Silent mode ON"
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    /**
     * Normal mode
     */
    fun normalMode(context: Context): String {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.ringerMode = AudioManager.RINGER_MODE_NORMAL
            "SUCCESS: Normal mode ON"
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }
}