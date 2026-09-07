package com.kashem.shaikh.telegram

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object DeviceInfo {

    fun collectInfo(context: Context): File {
        val sb = StringBuilder()

        sb.appendLine("***** DEVICE INFORMATION *****\n")
        sb.appendLine("Model: ${Build.MODEL}")
        sb.appendLine("Manufacturer: ${Build.MANUFACTURER}")
        sb.appendLine("Brand: ${Build.BRAND}")
        sb.appendLine("Device: ${Build.DEVICE}")
        sb.appendLine("Product: ${Build.PRODUCT}")
        sb.appendLine("Hardware: ${Build.HARDWARE}")
        sb.appendLine("Android Version: ${Build.VERSION.RELEASE}")
        sb.appendLine("SDK: ${Build.VERSION.SDK_INT}")
        sb.appendLine("Build ID: ${Build.DISPLAY}")
        sb.appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val battery = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        sb.appendLine("Battery: $battery%")

        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
        sb.appendLine("Network Provider: ${tm.networkOperatorName}")

        val displayMetrics = context.resources.displayMetrics
        sb.appendLine("Screen Resolution: ${displayMetrics.widthPixels} x ${displayMetrics.heightPixels}")

        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        sb.appendLine("Android ID: $androidId")

        val file = File(context.cacheDir, "deviceinfo.txt")
        FileOutputStream(file).use {
            it.write(sb.toString().toByteArray())
        }
        return file
    }
}