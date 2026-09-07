package com.kashem.shaikh.telegram

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.kashem.shaikh.AppLogs
import androidx.core.content.ContextCompat

object PhoneCall {

    fun makeCall(context: Context, phoneNumber: String, simSlot: Int): Boolean {
        if (phoneNumber.isBlank()) {
            AppLogs.warn("PhoneCall: ফোন নম্বর খালি")
            return false
        }

        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            AppLogs.error("PhoneCall: CALL_PHONE পারমিশন নেই")
            return false
        }

        return try {
            val subscriptionId = getSubscriptionIdForSimSlot(context, simSlot)
            if (subscriptionId == null) {
                AppLogs.warn("PhoneCall: সিম স্লট $simSlot এর জন্য সাবস্ক্রিপশন আইডি পাওয়া যায়নি। ডিফল্ট সিম ব্যবহার করছি।")
            }

            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.fromParts("tel", phoneNumber, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                // একাধিক এক্সট্রা যোগ করছি – বিভিন্ন ডিভাইসের জন্য
                if (subscriptionId != null) {
                    putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, subscriptionId)
                    // কিছু ডিভাইসে এই এক্সট্রা কাজ করে
                    putExtra("com.android.phone.extra.slot", simSlot - 1)
                    // আরও কিছু ডিভাইসের জন্য
                    putExtra("slot", simSlot - 1)
                    // Samsung ডিভাইসের জন্য
                    putExtra("simSlot", simSlot - 1)
                }
            }

            context.startActivity(intent)
            AppLogs.info("📞 ফোন কল শুরু: $phoneNumber, সিম স্লট=$simSlot (subscriptionId=$subscriptionId)")
            true
        } catch (e: SecurityException) {
            AppLogs.error("PhoneCall: SecurityException - পারমিশন নেই? ${e.message}", e)
            false
        } catch (e: Exception) {
            AppLogs.error("PhoneCall: কল করতে ব্যর্থ: ${e.message}", e)
            false
        }
    }

    private fun getSubscriptionIdForSimSlot(context: Context, simSlot: Int): Int? {
        return try {
            val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val subscriptionInfos = subscriptionManager.activeSubscriptionInfoList
            if (subscriptionInfos == null || subscriptionInfos.isEmpty()) {
                AppLogs.warn("PhoneCall: কোনো সক্রিয় সিম পাওয়া যায়নি")
                return null
            }

            val index = simSlot - 1
            if (index >= 0 && index < subscriptionInfos.size) {
                val subId = subscriptionInfos[index].subscriptionId
                AppLogs.debug("PhoneCall: সিম স্লট $simSlot -> subscriptionId $subId")
                return subId
            } else {
                AppLogs.warn("PhoneCall: সিম স্লট $simSlot ইনভ্যালিড, ডিফল্ট সিম ব্যবহার করছি")
                subscriptionInfos.firstOrNull()?.subscriptionId
            }
        } catch (e: Exception) {
            AppLogs.error("PhoneCall: সাবস্ক্রিপশন আইডি পেতে ব্যর্থ: ${e.message}", e)
            null
        }
    }
}