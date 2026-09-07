package com.kashem.shaikh.telegram

import android.content.Context
import android.os.PowerManager
import android.util.Log

object WakeLock {
    private const val TAG = "WakeLock"
    private const val WAKE_LOCK_TAG = "Androbox::WakeLock"

    private var wakeLock: PowerManager.WakeLock? = null
    private var isLocked = false

    /**
     * Wake Lock চালু করুন (CPU জাগ্রত রাখে, স্ক্রিন অফ থাকলেও)
     * @param context Context (ApplicationContext)
     * @return true যদি সফল হয়
     */
    fun acquire(context: Context): Boolean {
        return try {
            if (wakeLock == null) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    WAKE_LOCK_TAG
                )
                // টাইমআউট ছাড়া ধরে রাখুন
                wakeLock?.acquire()
                isLocked = true
                Log.d(TAG, "✅ WakeLock acquired (unlimited)")
                true
            } else if (wakeLock?.isHeld == false) {
                wakeLock?.acquire()
                isLocked = true
                Log.d(TAG, "✅ WakeLock re-acquired")
                true
            } else {
                Log.d(TAG, "WakeLock already held")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock: ${e.message}")
            false
        }
    }

    /**
     * Wake Lock বন্ধ করুন (CPU স্বাভাবিক অবস্থায় ফিরে যাবে)
     * @return true যদি সফল হয়
     */
    fun release(): Boolean {
        return try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                isLocked = false
                Log.d(TAG, "❌ WakeLock released")
                true
            } else {
                Log.d(TAG, "WakeLock already released")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WakeLock: ${e.message}")
            false
        } finally {
            wakeLock = null
        }
    }

    /**
     * Wake Lock চালু আছে কিনা চেক করুন
     */
    fun isHeld(): Boolean {
        return wakeLock?.isHeld == true || isLocked
    }

    /**
     * Wake Lock রিলিজ করে নাল করুন (জোর করে)
     */
    fun forceRelease() {
        try {
            wakeLock?.release()
            wakeLock = null
            isLocked = false
            Log.d(TAG, "⚠️ WakeLock force released")
        } catch (e: Exception) {
            Log.e(TAG, "Force release failed: ${e.message}")
        }
    }

    /**
     * Wake Lock স্ট্যাটাস টেক্সট ফেরত দিন
     */
    fun getStatus(): String {
        return if (isHeld()) {
            "🔋 WakeLock: ACTIVE (CPU awake)"
        } else {
            "🔋 WakeLock: INACTIVE (CPU can sleep)"
        }
    }
}