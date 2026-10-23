package com.kashem.shaikh.actions

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import com.kashem.shaikh.services.AppLogs

object AddContact {

    /**
     * নতুন কন্ট্যাক্ট যোগ করে (duplicate check সহ)
     */
    fun addContact(context: Context, name: String, phoneNumber: String): String {
        if (name.isBlank()) return "ERROR: Name is empty"
        if (phoneNumber.isBlank()) return "ERROR: Number is empty"

        // ✅ Duplicate check
        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.NUMBER} = ?",
                arrayOf(phoneNumber),
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    AppLogs.warn("AddContact: duplicate number $phoneNumber")
                    return "ERROR: Number already exists"
                }
            }
        } catch (e: Exception) {
            AppLogs.warn("AddContact: duplicate check failed – ${e.message}")
        }

        val resolver: ContentResolver = context.contentResolver

        return try {
            val ops = ArrayList<ContentProviderOperation>()

            // ১. RawContact
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null as String?)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null as String?)
                    .build()
            )

            // ২. Display name
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                    )
                    .withValue(
                        ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                        name
                    )
                    .build()
            )

            // ৩. Phone number
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
                    )
                    .withValue(
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        phoneNumber
                    )
                    .withValue(
                        ContactsContract.CommonDataKinds.Phone.TYPE,
                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                    )
                    .build()
            )

            val results = resolver.applyBatch(ContactsContract.AUTHORITY, ops)

            var createdUri: Uri? = null
            var ok = false

            results.forEachIndexed { i, r ->
                val count = r.count ?: 0
                val uri = r.uri
                AppLogs.debug("AddContact: result[$i] count=$count uri=$uri")
                if (uri != null) {
                    ok = true
                    if (i == 0) createdUri = uri
                }
                if (count > 0) ok = true
            }

            if (ok) {
                val id = createdUri?.lastPathSegment ?: "?"
                AppLogs.info("AddContact: '$name' ($phoneNumber) added, rawId=$id")
                "SUCCESS: Contact '$name' added"
            } else {
                AppLogs.warn("AddContact: all results empty for '$name'")
                "ERROR: Failed to add contact"
            }
        } catch (e: SecurityException) {
            AppLogs.error("AddContact: permission denied", e)
            "ERROR: WRITE_CONTACTS permission not granted"
        } catch (e: Exception) {
            AppLogs.error("AddContact: failed", e)
            "ERROR: ${e.message}"
        }
    }
}