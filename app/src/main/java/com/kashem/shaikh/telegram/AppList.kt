package com.kashem.shaikh.telegram

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object AppList {

    fun collectApps(context: Context): File {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        data class AppInfo(
            val appName: String,
            val packageName: String,
            val versionName: String,
            val versionCode: Long,  // Long এ পরিবর্তন
            val installDate: String,
            val updateDate: String,
            val apkSizeKB: Long,
            val isSystem: Boolean,
            val targetSdk: Int,
            val minSdk: Int,
            val installer: String,
            val permissionCount: Int,
            val enabledState: String
        )

        val appInfoList = mutableListOf<AppInfo>()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        for (app in apps) {
            try {
                val packageInfo = pm.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
                val appName = pm.getApplicationLabel(app).toString()
                val versionName = packageInfo.versionName ?: "N/A"
                val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    packageInfo.versionCode.toLong()
                }
                val installDate = dateFormat.format(Date(packageInfo.firstInstallTime))
                val updateDate = dateFormat.format(Date(packageInfo.lastUpdateTime))
                val apkSize = File(app.sourceDir).length() / 1024
                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                val targetSdk = app.targetSdkVersion
                val minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    app.minSdkVersion
                } else {
                    packageInfo.applicationInfo?.minSdkVersion ?: 0
                }

                // নতুন API: getInstallSourceInfo (Android 30+)
                val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    pm.getInstallSourceInfo(app.packageName)?.installingPackageName ?: "Unknown"
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstallerPackageName(app.packageName) ?: "Unknown"
                }

                val permissionCount = packageInfo.requestedPermissions?.size ?: 0

                val enabledSetting = pm.getApplicationEnabledSetting(app.packageName)
                val enabledState = when (enabledSetting) {
                    1 -> "Enabled"
                    2 -> "Disabled"
                    3 -> "Disabled (User)"
                    4 -> "Disabled Until Used"
                    else -> "Unknown"
                }

                appInfoList.add(
                    AppInfo(
                        appName = appName,
                        packageName = app.packageName,
                        versionName = versionName,
                        versionCode = versionCode,
                        installDate = installDate,
                        updateDate = updateDate,
                        apkSizeKB = apkSize,
                        isSystem = isSystem,
                        targetSdk = targetSdk,
                        minSdk = minSdk,
                        installer = installer,
                        permissionCount = permissionCount,
                        enabledState = enabledState
                    )
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val sortedApps = appInfoList.sortedWith(compareBy<AppInfo> { it.isSystem }.thenBy { it.appName })

        val totalApps = sortedApps.size
        val userApps = sortedApps.count { !it.isSystem }
        val systemApps = sortedApps.count { it.isSystem }

        val sb = StringBuilder()
        sb.appendLine("═══════════════════════════════════════════════════════")
        sb.appendLine("         📱 INSTALLED APPS (ADVANCED REPORT)         ")
        sb.appendLine("═══════════════════════════════════════════════════════")
        sb.appendLine()
        sb.appendLine("📅 Generated : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        sb.appendLine()
        sb.appendLine("📊 Summary")
        sb.appendLine("   Total Apps    : $totalApps")
        sb.appendLine("   👤 User Apps   : $userApps")
        sb.appendLine("   ⚙️ System Apps : $systemApps")
        sb.appendLine()
        sb.appendLine("───────────────────────────────────────────────────────")
        sb.appendLine()

        var currentSection = -1
        for (info in sortedApps) {
            val section = if (info.isSystem) 1 else 0
            if (section != currentSection) {
                currentSection = section
                sb.appendLine()
                if (section == 0) {
                    sb.appendLine("👤  USER APPLICATIONS")
                } else {
                    sb.appendLine("⚙️  SYSTEM APPLICATIONS")
                }
                sb.appendLine("───────────────────────────────────────────────────────")
            }

            val icon = if (info.isSystem) "⚙️" else "👤"
            sb.appendLine("$icon ${info.appName}")
            sb.appendLine("   Package       : ${info.packageName}")
            sb.appendLine("   Version       : ${info.versionName} (code ${info.versionCode})")
            sb.appendLine("   Installed     : ${info.installDate}")
            sb.appendLine("   Updated       : ${info.updateDate}")
            sb.appendLine("   APK Size      : ${info.apkSizeKB} KB")
            sb.appendLine("   Target SDK    : ${info.targetSdk}")
            sb.appendLine("   Min SDK       : ${info.minSdk}")
            sb.appendLine("   Installer     : ${info.installer}")
            sb.appendLine("   Permissions   : ${info.permissionCount}")
            sb.appendLine("   Status        : ${info.enabledState}")
            sb.appendLine("   Type          : ${if (info.isSystem) "System" else "User"}")
            sb.appendLine("───────────────────────────────────────────────────────")
        }

        sb.appendLine()
        sb.appendLine("═══════════════════════════════════════════════════════")
        sb.appendLine("  End of Report  |  Total: $totalApps apps")
        sb.appendLine("═══════════════════════════════════════════════════════")

        val file = File(context.cacheDir, "AppList.txt")
        FileOutputStream(file).use { it.write(sb.toString().toByteArray()) }
        return file
    }
}