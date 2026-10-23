package com.kashem.shaikh.actions

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import com.kashem.shaikh.services.AppLogs

object SendSms {

    /**
     * SMS পাঠায় (multipart auto-handled)
     */
    fun send(context: Context, number: String, text: String): String {
        if (number.isBlank()) return "ERROR: number empty"
        if (text.isBlank()) return "ERROR: text empty"

        return try {
            val sm: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
                    ?: return "ERROR: SmsManager not available"
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            val parts = sm.divideMessage(text)
            if (parts.size > 1) {
                sm.sendMultipartTextMessage(number, null, parts, null, null)
            } else {
                sm.sendTextMessage(number, null, text, null, null)
            }

            AppLogs.info("SendSms: → $number (${text.length} chars, ${parts.size} parts)")
            "SUCCESS: SMS sent to $number"
        } catch (e: SecurityException) {
            AppLogs.error("SendSms: permission denied", e)
            "ERROR: SEND_SMS permission not granted"
        } catch (e: Exception) {
            AppLogs.error("SendSms: failed", e)
            "ERROR: ${e.message}"
        }
    }
}