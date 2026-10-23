package com.kashem.shaikh.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.kashem.shaikh.services.AppLogs

object AppControl {

    fun launch(context: Context, packageName: String): String {
        if (packageName.isBlank()) return "ERROR: package name empty"

        return try {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(packageName)
                ?: return "ERROR: No launcher activity for $packageName"
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
            AppLogs.info("AppControl: launched $packageName")
            "SUCCESS: Launched $packageName"
        } catch (e: Exception) {
            AppLogs.error("AppControl.launch failed", e)
            "ERROR: ${e.message}"
        }
    }

    fun uninstall(context: Context, packageName: String): String {
        if (packageName.isBlank()) return "ERROR: package name empty"

        return try {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$packageName")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            AppLogs.info("AppControl: uninstall dialog → $packageName")
            "SUCCESS: Uninstall dialog opened for $packageName"
        } catch (e: Exception) {
            AppLogs.error("AppControl.uninstall failed", e)
            "ERROR: ${e.message}"
        }
    }

    fun openAppInfo(context: Context, packageName: String): String {
        if (packageName.isBlank()) return "ERROR: package name empty"

        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "SUCCESS: Opened app info for $packageName"
        } catch (e: Exception) {
            AppLogs.error("AppControl.openAppInfo failed", e)
            "ERROR: ${e.message}"
        }
    }

    // ✅ FIX: পুরাতন doc comment ভুল ছিল ("force-stop করে") — ঠিক করা হয়েছে
    /**
     * Package ইনস্টল করা আছে কিনা চেক করে
     */
    fun isInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }
}