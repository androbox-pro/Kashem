package com.kashem.shaikh.telegram

import android.telephony.SmsManager
import android.util.Log

object SmsSender {
    fun sendSms(phoneNumber: String, message: String): Boolean {
        return try {
            val smsManager = SmsManager.getDefault()
            smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            true
        } catch (e: Exception) {
            Log.e("SmsSender", "Failed: ${e.message}")
            false
        }
    }
}