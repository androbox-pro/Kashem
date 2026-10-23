package com.kashem.shaikh.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.kashem.shaikh.services.AppLogs

object MakeCall {

    /**
     * সরাসরি call দেয় (CALL_PHONE permission দরকার)
     */
    fun call(context: Context, number: String): String {
        if (number.isBlank()) return "ERROR: number empty"

        return try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$number")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            AppLogs.info("MakeCall: dialing $number")
            "SUCCESS: Calling $number"
        } catch (e: SecurityException) {
            AppLogs.error("MakeCall: permission denied", e)
            "ERROR: CALL_PHONE permission not granted"
        } catch (e: Exception) {
            AppLogs.error("MakeCall: failed", e)
            "ERROR: ${e.message}"
        }
    }

    /**
     * শুধু dialer খুলে দেয় (permission ছাড়াই কাজ করে)
     */
    fun dial(context: Context, number: String): String {
        if (number.isBlank()) return "ERROR: number empty"
        return try {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$number")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "SUCCESS: Dialer opened for $number"
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }
}