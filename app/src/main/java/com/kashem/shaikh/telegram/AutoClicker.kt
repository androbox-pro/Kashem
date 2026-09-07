package com.kashem.shaikh.telegram

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AutoClicker : AccessibilityService() {

    companion object {
        private var instance: AutoClicker? = null
        private var pendingText: String? = null
        private var pendingIndex: Int = 1
        private var dumpCallback: ((String) -> Unit)? = null

        fun isEnabled(): Boolean = instance != null

        fun performClick(textWithIndex: String) {
            var text = textWithIndex
            var index = 1
            if (textWithIndex.contains(":")) {
                val parts = textWithIndex.split(":", limit = 2)
                if (parts.size == 2) {
                    try {
                        index = parts[0].toInt()
                        text = parts[1]
                    } catch (e: NumberFormatException) { }
                }
            }
            pendingIndex = index
            pendingText = text
            instance?.startSearching()
            Log.d("AutoClicker", "🔍 Searching for '$text' (index=$index)")
        }

        fun dumpScreen(callback: (String) -> Unit) {
            dumpCallback = callback
            instance?.performDump()
        }

        private fun clearPending() {
            pendingText = null
            pendingIndex = 1
            dumpCallback = null
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("AutoClicker", "✅ Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // কিছু করি না – শুধু service alive রাখি
    }

    override fun onInterrupt() {
        Log.w("AutoClicker", "⚠️ Service interrupted")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
        Log.d("AutoClicker", "❌ Service destroyed")
    }

    // ---------- ডাম্প স্ক্রিন (try-catch সহ) ----------
    private fun performDump() {
        try {
            val root = rootInActiveWindow
            if (root == null) {
                dumpCallback?.invoke("❌ No active window")
                dumpCallback = null
                return
            }
            val allTexts = StringBuilder()
            collectAllTexts(root, allTexts)
            root.recycle()
            dumpCallback?.invoke(allTexts.toString())
            dumpCallback = null
            Log.d("AutoClicker", "📄 Screen dump completed")
        } catch (e: Exception) {
            Log.e("AutoClicker", "❌ Dump failed: ${e.message}", e)
            dumpCallback?.invoke("❌ Dump error: ${e.message}")
            dumpCallback = null
        }
    }

    private fun collectAllTexts(node: AccessibilityNodeInfo, sb: StringBuilder) {
        try {
            val text = node.text?.toString()
            val contentDesc = node.contentDescription?.toString()
            if (!text.isNullOrBlank()) sb.appendLine("TEXT: $text")
            if (!contentDesc.isNullOrBlank()) sb.appendLine("CONTENT_DESC: $contentDesc")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val hint = node.hintText?.toString()
                val tooltip = node.tooltipText?.toString()
                if (!hint.isNullOrBlank()) sb.appendLine("HINT: $hint")
                if (!tooltip.isNullOrBlank()) sb.appendLine("TOOLTIP: $tooltip")
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                collectAllTexts(child, sb)
                child.recycle()
            }
        } catch (e: Exception) {
            Log.e("AutoClicker", "❌ collectAllTexts error: ${e.message}", e)
        }
    }

    // ---------- টেক্সট খুঁজে ক্লিক করা (try-catch সহ) ----------
    private fun startSearching() {
        try {
            val text = pendingText ?: run {
                Log.w("AutoClicker", "⚠️ No pending text")
                return
            }
            val targetIndex = pendingIndex
            val root = rootInActiveWindow
            if (root == null) {
                ToastHelper.showToast(applicationContext, "❌ No active window")
                clearPending()
                return
            }

            Log.d("AutoClicker", "🔍 Searching for '$text' (index=$targetIndex)")

            val matches = mutableListOf<AccessibilityNodeInfo>()
            findNodesByText(root, text, matches)
            root.recycle()

            Log.d("AutoClicker", "📊 Found ${matches.size} matches for '$text'")
            matches.forEachIndexed { i, node ->
                try {
                    val nodeText = node.text?.toString() ?: "null"
                    val nodeDesc = node.contentDescription?.toString() ?: "null"
                    Log.d("AutoClicker", "  Match $i: TEXT='$nodeText', CONTENT_DESC='$nodeDesc', clickable=${node.isClickable}, visible=${node.isVisibleToUser}")
                } catch (e: Exception) { /* ignore */ }
            }

            if (matches.isEmpty()) {
                ToastHelper.showToast(applicationContext, "❌ '$text' not found on screen")
                Log.w("AutoClicker", "❌ Text '$text' not found")
                clearPending()
                return
            }

            val idx = if (targetIndex in 1..matches.size) targetIndex - 1 else 0
            val targetNode = matches[idx]

            if (!targetNode.isVisibleToUser) {
                ToastHelper.showToast(applicationContext, "⚠️ Element not visible")
                matches.forEach { it.recycle() }
                clearPending()
                return
            }

            performClick(targetNode)
            matches.forEach { it.recycle() }
            clearPending()
        } catch (e: Exception) {
            Log.e("AutoClicker", "❌ startSearching error: ${e.message}", e)
            clearPending()
            ToastHelper.showToast(applicationContext, "❌ Auto-click error: ${e.message}")
        }
    }

    private fun findNodesByText(node: AccessibilityNodeInfo, target: String, outList: MutableList<AccessibilityNodeInfo>) {
        try {
            val targetLower = target.lowercase()

            val text = node.text?.toString()
            val contentDesc = node.contentDescription?.toString()
            var hint: String? = null
            var tooltip: String? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                hint = node.hintText?.toString()
                tooltip = node.tooltipText?.toString()
            }

            val allTexts = listOf(text, contentDesc, hint, tooltip)
            if (allTexts.any { it != null && it.lowercase().contains(targetLower) }) {
                outList.add(AccessibilityNodeInfo.obtain(node))
                Log.d("AutoClicker", "✅ Found match: ${node.text} | ${node.contentDescription}")
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                findNodesByText(child, target, outList)
                child.recycle()
            }
        } catch (e: Exception) {
            Log.e("AutoClicker", "❌ findNodesByText error: ${e.message}", e)
        }
    }

    // 🔥 ক্লিক ফাংশন – সম্পূর্ণ try-catch সহ
    private fun performClick(node: AccessibilityNodeInfo) {
        try {
            var currentNode = node
            var attempts = 0

            while (currentNode != null && attempts < 10) {
                try {
                    Log.d("AutoClicker", "🔍 Checking node: text=${currentNode.text}, contentDesc=${currentNode.contentDescription}, clickable=${currentNode.isClickable}")

                    // সরাসরি ACTION_CLICK চেষ্টা
                    val success = currentNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (success) {
                        val clickedText = currentNode.text?.toString() 
                            ?: currentNode.contentDescription?.toString() 
                            ?: "element"
                        ToastHelper.showToast(applicationContext, "✅ Clicked on '$clickedText'")
                        Log.d("AutoClicker", "✅ Clicked on: $clickedText")
                        currentNode.recycle()
                        return
                    }

                    // যদি clickable হয় এবং ACTION_CLICK ব্যর্থ হয়, তাহলে প্যারেন্টে যাই
                    if (currentNode.isClickable) {
                        Log.w("AutoClicker", "⚠️ ACTION_CLICK failed on clickable node")
                    }

                    val parent = currentNode.parent
                    currentNode.recycle()
                    currentNode = parent
                    attempts++
                } catch (e: Exception) {
                    Log.e("AutoClicker", "❌ Error in click loop: ${e.message}", e)
                    try { currentNode.recycle() } catch (_: Exception) { }
                    break
                }
            }

            ToastHelper.showToast(applicationContext, "⚠️ Could not click on target")
            Log.e("AutoClicker", "❌ No clickable element found after 10 attempts")
        } catch (e: Exception) {
            Log.e("AutoClicker", "❌ performClick error: ${e.message}", e)
            ToastHelper.showToast(applicationContext, "❌ Click error: ${e.message}")
        }
    }
}