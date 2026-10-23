package com.kashem.shaikh.streaming

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.kashem.shaikh.services.AppLogs

class DeviceControlService : AccessibilityService() {

    companion object {
        @Volatile private var instance: DeviceControlService? = null

        private const val MAX_SEARCH_DEPTH = 30

        fun isEnabled(): Boolean = instance != null

        fun tap(x: Float, y: Float): Boolean {
            val s = instance ?: return false
            return try {
                val path = Path().apply { moveTo(x, y) }
                val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                s.dispatchGesture(gesture, null, null)
            } catch (e: Exception) {
                AppLogs.error("tap error", e); false
            }
        }

        fun longPress(x: Float, y: Float, durationMs: Long = 800L): Boolean {
            val s = instance ?: return false
            return try {
                val path = Path().apply { moveTo(x, y) }
                val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                s.dispatchGesture(gesture, null, null)
            } catch (e: Exception) {
                AppLogs.error("longPress error", e); false
            }
        }

        fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
            val s = instance ?: return false
            return try {
                val path = Path().apply {
                    moveTo(x1, y1)
                    lineTo(x2, y2)
                }
                val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                s.dispatchGesture(gesture, null, null)
            } catch (e: Exception) {
                AppLogs.error("swipe error", e); false
            }
        }

        fun pinch(
            cx: Float, cy: Float,
            startRadius: Float, endRadius: Float,
            durationMs: Long = 300L
        ): Boolean {
            val s = instance ?: return false
            return try {
                val path1 = Path().apply {
                    moveTo(cx - startRadius, cy)
                    lineTo(cx - endRadius, cy)
                }
                val path2 = Path().apply {
                    moveTo(cx + startRadius, cy)
                    lineTo(cx + endRadius, cy)
                }
                val stroke1 = GestureDescription.StrokeDescription(path1, 0L, durationMs)
                val stroke2 = GestureDescription.StrokeDescription(path2, 0L, durationMs)
                val gesture = GestureDescription.Builder()
                    .addStroke(stroke1)
                    .addStroke(stroke2)
                    .build()
                s.dispatchGesture(gesture, null, null)
            } catch (e: Exception) {
                AppLogs.error("pinch error", e); false
            }
        }

        fun globalAction(action: String): Boolean {
            val s = instance ?: return false
            return try {
                val code = when (action.lowercase()) {
                    "back"           -> GLOBAL_ACTION_BACK
                    "home"           -> GLOBAL_ACTION_HOME
                    "recents"        -> GLOBAL_ACTION_RECENTS
                    "notifications"  -> GLOBAL_ACTION_NOTIFICATIONS
                    "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
                    "power_dialog"   -> GLOBAL_ACTION_POWER_DIALOG
                    "lock_screen"    -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else -1
                    "screenshot"     -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_TAKE_SCREENSHOT else -1
                    else -> -1
                }
                if (code == -1) false else s.performGlobalAction(code)
            } catch (e: Exception) {
                AppLogs.error("globalAction error", e); false
            }
        }

        fun clickByText(text: String): Boolean {
            val s = instance ?: return false
            return try {
                val root = s.rootInActiveWindow ?: return false
                val node = findNodeByText(root, text, 0)
                val result = node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
                node?.recycle()
                root.recycle()
                result
            } catch (e: Exception) {
                AppLogs.error("clickByText error", e); false
            }
        }

        fun typeText(text: String): Boolean {
            val s = instance ?: return false
            return try {
                val root = s.rootInActiveWindow ?: return false
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused == null) {
                    root.recycle(); return false
                }
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text
                    )
                }
                val ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                focused.recycle()
                root.recycle()
                ok
            } catch (e: Exception) {
                AppLogs.error("typeText error", e); false
            }
        }

        // ✅ FIX: depth limit — deep UI tree-এ stack overflow এড়াতে
        private fun findNodeByText(
            node: AccessibilityNodeInfo,
            text: String,
            depth: Int
        ): AccessibilityNodeInfo? {
            if (depth > MAX_SEARCH_DEPTH) return null

            val nodeText = node.text?.toString() ?: ""
            if (nodeText.contains(text, ignoreCase = true)) {
                return AccessibilityNodeInfo.obtain(node)
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = findNodeByText(child, text, depth + 1)
                child.recycle()
                if (found != null) return found
            }
            return null
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AppLogs.info("DeviceControlService: ✅ connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* injection-only */ }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        AppLogs.info("DeviceControlService: destroyed")
        super.onDestroy()
    }
}