package com.kashem.shaikh.telegram

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log

object ClipboardHelper {
    private const val TAG = "ClipboardHelper"

    fun getClipboardText(context: Context): String {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                clipboard.getPrimaryClip()?.getItemAt(0)?.text?.toString() ?: ""
            } else {
                @Suppress("DEPRECATION")
                clipboard.text?.toString() ?: ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get clipboard: ${e.message}")
            ""
        }
    }

    fun setClipboardText(context: Context, text: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Copied Text", text)
            clipboard.setPrimaryClip(clip)
            Log.d(TAG, "Clipboard set: $text")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set clipboard: ${e.message}")
        }
    }
}