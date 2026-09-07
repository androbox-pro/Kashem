package com.kashem.shaikh.telegram

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

object AppUninstall {

    /**
     * যেকোনো প্যাকেজ নামের জন্য সরাসরি App Info পেজ খোলে (কোনো অস্তিত্ব যাচাই বাদে)
     * এটি সব Android ভার্সন ও সব কাস্টম ROM-এ কাজ করে।
     */
    fun openAppInfo(context: Context, rawPackageName: String): Boolean {
        val packageName = rawPackageName.trim().lowercase()
        if (packageName.isEmpty()) {
            ToastHelper.showToast(context, "Package name empty")
            return false
        }

        Log.d("AppUninstall", "Opening App Info for: $packageName")

        val intent = Intent().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                data = Uri.parse("package:$packageName")
            } else {
                action = Intent.ACTION_VIEW
                data = Uri.parse("package:$packageName")
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        return try {
            context.startActivity(intent)
            ToastHelper.showToast(context, "Opening App Info for $packageName")
            Log.d("AppUninstall", "Intent started successfully")
            true
        } catch (e: Exception) {
            val msg = "Failed to open App Info: ${e.message}"
            ToastHelper.showToast(context, msg)
            Log.e("AppUninstall", msg, e)
            false
        }
    }
}