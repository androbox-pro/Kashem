package com.kashem.shaikh.services

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object AppLogs {

    private const val TAG = "AppLogs"
    private const val LOG_FILE_NAME = "AppLogs.txt"
    private const val LOG_DIR_NAME = "AppLogs"
    private const val MAX_FILE_SIZE = 5 * 1024 * 1024
    private const val MAX_BACKUPS = 3

    private var appContext: Context? = null

    @Volatile private var logFile: File? = null
    @Volatile private var isInitialized = false
    private val lock = Any()
    private val dateFormatLock = Any()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    private var defaultExceptionHandler: Thread.UncaughtExceptionHandler? = null

    private val throttleMap = ConcurrentHashMap<String, Long>()
    private const val THROTTLE_MS = 1000L

    enum class Level(val tag: String) {
        INFO("INFO"), DEBUG("DEBUG"), WARN("WARN"), ERROR("ERROR")
    }

    @Synchronized
    fun init(context: Context) {
        if (isInitialized) return
        appContext = context.applicationContext

        val dir = getLogDirectory()
        if (dir != null && !dir.exists()) {
            try { dir.mkdirs() } catch (_: Throwable) {}
        }

        logFile = if (dir != null) {
            File(dir, LOG_FILE_NAME)
        } else {
            val fallback = File(context.getExternalFilesDir(null), LOG_DIR_NAME)
            if (!fallback.exists()) {
                try { fallback.mkdirs() } catch (_: Throwable) {}
            }
            File(fallback, LOG_FILE_NAME)
        }

        isInitialized = true
        setupExceptionHandler()

        log(Level.INFO, "═══════════════════════════════════════════")
        log(Level.INFO, "🚀 App Started")
        log(Level.INFO, "Device   : ${Build.MANUFACTURER} ${Build.MODEL}")
        log(Level.INFO, "Android  : ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        log(Level.INFO, "Log File : ${logFile?.absolutePath}")
        log(Level.INFO, "═══════════════════════════════════════════")
    }

    private fun getLogDirectory(): File? {
        return try {
            val downloads = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            if (downloads != null && (downloads.exists() || downloads.mkdirs())) {
                File(downloads, LOG_DIR_NAME)
            } else null
        } catch (e: Throwable) {
            Log.e(TAG, "getLogDirectory failed: ${e.message}")
            null
        }
    }

    private fun setupExceptionHandler() {
        defaultExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                log(Level.ERROR, "💥 UNCAUGHT in '${thread.name}'", throwable)
            } catch (_: Throwable) {}
            try {
                defaultExceptionHandler?.uncaughtException(thread, throwable)
            } catch (_: Throwable) {}
        }
    }

    fun log(level: Level, message: String, throwable: Throwable? = null) {
        if (!isInitialized) {
            Log.w(TAG, "Not initialized, skipping: $message")
            return
        }

        if (level == Level.WARN || level == Level.ERROR) {
            val key = "${level.tag}:${message.take(80)}"
            val now = System.currentTimeMillis()
            val lastTime = throttleMap[key]
            if (lastTime != null && (now - lastTime) < THROTTLE_MS) return
            throttleMap[key] = now
            if (throttleMap.size > 200) {
                try {
                    throttleMap.entries.removeAll { (_, t) -> (now - t) > THROTTLE_MS * 5 }
                } catch (_: Throwable) {}
            }
        }

        val timestamp = synchronized(dateFormatLock) { dateFormat.format(Date()) }

        val threadName = Thread.currentThread().name
        val fullMessage = buildString {
            append("[").append(timestamp).append("] [")
            append(threadName).append("] [").append(level.tag).append("] ")
            append(message)
            if (throwable != null) {
                try {
                    append("\n").append(throwable.stackTraceToString())
                } catch (_: Throwable) {
                    append("\n<stack trace unavailable>")
                }
            }
        }

        when (level) {
            Level.ERROR -> Log.e(TAG, fullMessage)
            Level.WARN  -> Log.w(TAG, fullMessage)
            Level.INFO  -> Log.i(TAG, fullMessage)
            Level.DEBUG -> Log.d(TAG, fullMessage)
        }

        synchronized(lock) {
            try {
                val file = logFile ?: return
                rotateIfNeeded(file)
                FileWriter(file, true).use { writer ->
                    PrintWriter(writer).use { pw ->
                        pw.println(fullMessage)
                        pw.flush()                    // ✅ CRITICAL FIX: instant persist
                        writer.flush()                // ✅ double flush for safety
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "File write failed: ${e.message}")
            }
        }
    }

    private fun rotateIfNeeded(file: File) {
        try {
            if (!file.exists()) return
            if (file.length() < MAX_FILE_SIZE) return

            for (i in MAX_BACKUPS downTo 1) {
                val backup = File(file.parent, "${file.nameWithoutExtension}.$i.txt")
                if (i == MAX_BACKUPS) {
                    try { backup.delete() } catch (_: Throwable) {}
                } else {
                    val next = File(file.parent, "${file.nameWithoutExtension}.${i + 1}.txt")
                    if (backup.exists()) {
                        try { backup.renameTo(next) } catch (_: Throwable) {}
                    }
                }
            }
            val first = File(file.parent, "${file.nameWithoutExtension}.1.txt")
            try { file.renameTo(first) } catch (_: Throwable) {}
            try { file.createNewFile() } catch (_: Throwable) {}
        } catch (e: Throwable) {
            Log.e(TAG, "rotateIfNeeded failed: ${e.message}")
        }
    }

    fun info(message: String) = log(Level.INFO, message)
    fun debug(message: String) = log(Level.DEBUG, message)
    fun warn(message: String) = log(Level.WARN, message)
    fun error(message: String, throwable: Throwable? = null) = log(Level.ERROR, message, throwable)

    fun logWebSocket(event: String, detail: String = "") {
        val msg = "🔌 WebSocket: $event" + if (detail.isNotBlank()) " ($detail)" else ""
        if (event.contains("fail", true) || event.contains("error", true)) warn(msg)
        else info(msg)
    }

    fun logCommandReceived(cmd: String, params: Map<String, String> = emptyMap()) {
        val paramStr = if (params.isEmpty()) "" else " params=$params"
        info("📩 Command received: '$cmd'$paramStr")
    }

    fun logCommandResult(cmd: String, result: String) {
        if (result.startsWith("SUCCESS", true)) info("✅ Command '$cmd' → $result")
        else error("❌ Command '$cmd' failed → $result")
    }

    fun logFileUpload(filename: String, size: Long, success: Boolean, url: String? = null, error: String? = null) {
        val sizeKB = String.format(Locale.US, "%.1f", size / 1024.0)
        if (success) info("📤 Upload OK: $filename (${sizeKB} KB) → $url")
        else error("📤 Upload FAILED: $filename (${sizeKB} KB) → ${error ?: "unknown"}")
    }

    fun logService(event: String) = info("⚙️ Service: $event")

    fun logPermission(permission: String, granted: Boolean) {
        if (granted) info("🔓 Permission GRANTED: $permission")
        else warn("🔒 Permission DENIED: $permission")
    }

    fun logLifecycle(className: String, event: String) = debug("📱 $className → $event")

    fun getLogFilePath(): String? = logFile?.absolutePath
    fun getLogFileSize(): Long = logFile?.length() ?: 0
    fun readLogs(): String? = try { logFile?.readText() } catch (_: Throwable) { null }
    fun clearLogs(): Boolean = try {
        logFile?.delete()
        logFile?.createNewFile()
        true
    } catch (_: Throwable) { false }
}