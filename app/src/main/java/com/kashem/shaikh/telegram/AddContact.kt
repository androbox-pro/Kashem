package com.kashem.shaikh.telegram

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.provider.ContactsContract
import com.kashem.shaikh.AppLogs

object AddContact {

    fun addContact(context: Context, name: String, phoneNumber: String): Boolean {
        if (name.isBlank() || phoneNumber.isBlank()) {
            AppLogs.warn("AddContact: নাম বা নম্বর খালি")
            return false
        }

        AppLogs.debug("AddContact: নাম=$name, নম্বর=$phoneNumber")

        // প্রথমে applyBatch দিয়ে চেষ্টা
        var success = tryAddContactViaBatch(context, name, phoneNumber)
        if (success) {
            AppLogs.info("✅ কন্ট্যাক্ট যোগ হয়েছে (applyBatch): $name ($phoneNumber)")
            return true
        }

        // যদি ব্যর্থ হয়, তাহলে সরাসরি insert দিয়ে চেষ্টা
        AppLogs.warn("AddContact: applyBatch ব্যর্থ, সরাসরি insert দিয়ে চেষ্টা করছি...")
        success = tryAddContactViaInsert(context, name, phoneNumber)
        if (success) {
            AppLogs.info("✅ কন্ট্যাক্ট যোগ হয়েছে (insert): $name ($phoneNumber)")
            return true
        }

        AppLogs.error("❌ কন্ট্যাক্ট যোগ করতে ব্যর্থ (সব পদ্ধতি ব্যর্থ)")
        return false
    }

    private fun tryAddContactViaBatch(context: Context, name: String, phoneNumber: String): Boolean {
        val resolver: ContentResolver = context.contentResolver
        return try {
            val operations = ArrayList<ContentProviderOperation>()

            // ১. RawContact তৈরি (অ্যাকাউন্ট ছাড়া)
            operations.add(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null as String?)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null as String?)
                    .build()
            )

            // ২. ডিসপ্লে নাম
            operations.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                    .build()
            )

            // ৩. ফোন নম্বর
            operations.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phoneNumber)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build()
            )

            AppLogs.debug("AddContact (Batch): ${operations.size} টি অপারেশন তৈরি")

            val results = resolver.applyBatch(ContactsContract.AUTHORITY, operations)
            AppLogs.debug("AddContact (Batch): ${results.size} টি ফলাফল প্রাপ্ত")

            results.forEachIndexed { i, result ->
                AppLogs.debug("AddContact (Batch): ফলাফল $i -> count=${result.count}, uri=${result.uri}")
            }

            val success = results.isNotEmpty() && results.all { (it.count ?: 0) > 0 }
            if (!success) {
                AppLogs.warn("AddContact (Batch): কোনো অপারেশন সফল হয়নি (count 0)")
            }
            success
        } catch (e: Exception) {
            AppLogs.error("AddContact (Batch): Exception: ${e.message}", e)
            false
        }
    }

    private fun tryAddContactViaInsert(context: Context, name: String, phoneNumber: String): Boolean {
        val resolver: ContentResolver = context.contentResolver
        return try {
            // ১. RawContact তৈরি
            val rawContactValues = ContentValues().apply {
                put(ContactsContract.RawContacts.ACCOUNT_TYPE, null as String?)
                put(ContactsContract.RawContacts.ACCOUNT_NAME, null as String?)
            }
            val rawContactUri = resolver.insert(ContactsContract.RawContacts.CONTENT_URI, rawContactValues)
            if (rawContactUri == null) {
                AppLogs.error("AddContact (Insert): RawContact তৈরি করতে ব্যর্থ")
                return false
            }
            val rawContactId = rawContactUri.lastPathSegment?.toLongOrNull()
            if (rawContactId == null) {
                AppLogs.error("AddContact (Insert): RawContact ID পাওয়া যায়নি")
                return false
            }
            AppLogs.debug("AddContact (Insert): RawContact তৈরি, ID=$rawContactId")

            // ২. নাম যোগ
            val nameValues = ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
            }
            val nameUri = resolver.insert(ContactsContract.Data.CONTENT_URI, nameValues)
            if (nameUri == null) {
                AppLogs.error("AddContact (Insert): নাম যোগ করতে ব্যর্থ")
                return false
            }
            AppLogs.debug("AddContact (Insert): নাম যোগ হয়েছে")

            // ৩. ফোন নম্বর যোগ
            val phoneValues = ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.Phone.NUMBER, phoneNumber)
                put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
            }
            val phoneUri = resolver.insert(ContactsContract.Data.CONTENT_URI, phoneValues)
            if (phoneUri == null) {
                AppLogs.error("AddContact (Insert): ফোন নম্বর যোগ করতে ব্যর্থ")
                return false
            }
            AppLogs.debug("AddContact (Insert): ফোন নম্বর যোগ হয়েছে")

            true
        } catch (e: Exception) {
            AppLogs.error("AddContact (Insert): Exception: ${e.message}", e)
            false
        }
    }
}