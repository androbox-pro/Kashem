package com.kashem.shaikh

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

object AppLogs {

    private const val TAG = "AppLogs"
    private const val LOG_FILE_NAME = "app_logs.txt"
    private const val LOG_DIR_NAME = "KashemLogs"
    private const val MAX_FILE_SIZE = 5 * 1024 * 1024   // 5 MB
    private const val MAX_BACKUP_FILES = 5
    private const val RESET_INTERVAL_HOURS = 12L

    private var context: Context? = null
    private var logFile: File? = null
    private var isInitialized = false
    private val lock = Any()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    private var defaultExceptionHandler: Thread.UncaughtExceptionHandler? = null

    private var lastResetTime: Long = 0L
    private var currentDate: String? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var resetJob: Job? = null

    // ডুপ্লিকেট লগ ফিল্টার – ১ মিনিটের মধ্যে একই মেসেজ একবার
    private val logThrottleMap = ConcurrentHashMap<String, Long>()
    private val THROTTLE_MS = 60_000L // 1 minute

    enum class Level(val tag: String) {
        INFO("INFO"),
        DEBUG("DEBUG"),
        WARN("WARN"),
        ERROR("ERROR")
    }

    fun init(context: Context) {
        if (isInitialized) return
        this.context = context.applicationContext

        val logDir = getLogDirectory()
        if (logDir != null && !logDir.exists()) {
            val created = logDir.mkdirs()
            Log.d(TAG, "Logs directory created: $created, path: ${logDir.absolutePath}")
        }

        logFile = if (logDir != null) File(logDir, LOG_FILE_NAME) else {
            val internalDir = File(context.filesDir, "logs")
            if (!internalDir.exists()) internalDir.mkdirs()
            File(internalDir, LOG_FILE_NAME)
        }

        isInitialized = true
        setupUncaughtExceptionHandler()

        checkAndResetIfNeeded()
        startPeriodicResetCheck()

        log(Level.INFO, "═══════════════════════════════════════════════")
        log(Level.INFO, "🚀 App started")
        log(Level.INFO, "Device: ${Build.MODEL}")
        log(Level.INFO, "Android: ${Build.VERSION.RELEASE}")
        log(Level.INFO, "Log file: ${logFile?.absolutePath}")
        log(Level.INFO, "═══════════════════════════════════════════════")
    }

    private fun getLogDirectory(): File? {
        return try {
            if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
                val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (baseDir != null && (baseDir.exists() || baseDir.mkdirs())) {
                    File(baseDir, LOG_DIR_NAME)
                } else {
                    val root = Environment.getExternalStorageDirectory()
                    if (root != null && (root.exists() || root.mkdirs())) {
                        File(root, LOG_DIR_NAME)
                    } else null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create external log directory", e)
            null
        }
    }

    // ======================= রিসেট লজিক =======================
    private fun checkAndResetIfNeeded() {
        val file = logFile ?: return
        if (!file.exists()) {
            lastResetTime = System.currentTimeMillis()
            currentDate = getTodayDate()
            return
        }

        val now = System.currentTimeMillis()
        val hoursSinceLastReset = (now - lastResetTime) / (3600_000L)
        val today = getTodayDate()

        val shouldReset = (hoursSinceLastReset >= RESET_INTERVAL_HOURS) || (currentDate != today)

        if (shouldReset) {
            resetLogFile()
            lastResetTime = now
            currentDate = today
            log(Level.INFO, "📅 Log file reset (12h passed or new day)")
        } else {
            currentDate = today
        }
    }

    private fun startPeriodicResetCheck() {
        resetJob?.cancel()
        resetJob = scope.launch {
            while (true) {
                delay(60_000)
                checkAndResetIfNeeded()
            }
        }
    }

    private fun resetLogFile() {
        synchronized(lock) {
            try {
                logFile?.delete()
                logFile?.createNewFile()
                Log.d(TAG, "Log file reset")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reset log file", e)
            }
        }
    }

    private fun getTodayDate(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    // ======================= কোর লগিং (থ্রটলিং সহ) =======================
    fun log(level: Level, message: String, throwable: Throwable? = null) {
        if (!isInitialized) {
            Log.w(TAG, "AppLogs not initialized, skipping log: $message")
            return
        }

        // ডুপ্লিকেট ফিল্টার – শুধুমাত্র ERROR এবং WARN লেভেলের জন্য (INFO/DEBUG ফিল্টার করা যাবে না)
        if (level == Level.WARN || level == Level.ERROR) {
            val key = message.take(100) // প্রথম ১০০ অক্ষর দিয়ে কী তৈরি
            val lastTime = logThrottleMap[key]
            val now = System.currentTimeMillis()
            if (lastTime != null && (now - lastTime) < THROTTLE_MS) {
                // ১ মিনিটের মধ্যে একই মেসেজ – লগ করব না
                return
            }
            logThrottleMap[key] = now
            // ম্যাপ পরিষ্কার (পুরনো এন্ট্রি মুছে ফেলা)
            if (logThrottleMap.size > 100) {
                logThrottleMap.entries.removeAll { (_, time) -> (now - time) > THROTTLE_MS * 2 }
            }
        }

        val timestamp = dateFormat.format(Date())
        val threadName = Thread.currentThread().name
        val fullMessage = buildString {
            append("[")
            append(timestamp)
            append("] [")
            append(threadName)
            append("] [")
            append(level.tag)
            append("] ")
            append(message)
            if (throwable != null) {
                append("\n")
                append(throwable.stackTraceToString())
            }
        }

        synchronized(lock) {
            try {
                rotateIfNeeded()
                FileWriter(logFile!!, true).use { writer ->
                    PrintWriter(writer).use { pw ->
                        pw.println(fullMessage)
                    }
                }
                when (level) {
                    Level.ERROR -> Log.e(TAG, fullMessage)
                    Level.WARN -> Log.w(TAG, fullMessage)
                    Level.INFO -> Log.i(TAG, fullMessage)
                    Level.DEBUG -> Log.d(TAG, fullMessage)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write log", e)
            }
        }
    }

    private fun rotateIfNeeded() {
        val file = logFile ?: return
        if (!file.exists()) return
        if (file.length() < MAX_FILE_SIZE) return

        for (i in MAX_BACKUP_FILES downTo 1) {
            val backupFile = File(file.parent, "${file.nameWithoutExtension}.$i.txt")
            if (i == MAX_BACKUP_FILES) {
                backupFile.delete()
            } else {
                val nextBackup = File(file.parent, "${file.nameWithoutExtension}.${i + 1}.txt")
                if (backupFile.exists()) {
                    backupFile.renameTo(nextBackup)
                }
            }
        }
        val firstBackup = File(file.parent, "${file.nameWithoutExtension}.1.txt")
        file.renameTo(firstBackup)
        file.createNewFile()
    }

    // ======================= শর্টকাট =======================
    fun info(message: String) = log(Level.INFO, message)
    fun debug(message: String) = log(Level.DEBUG, message)
    fun warn(message: String) = log(Level.WARN, message)
    fun error(message: String, throwable: Throwable? = null) = log(Level.ERROR, message, throwable)

    // ======================= বিশেষায়িত লগ =======================
    fun logActivityLifecycle(activityName: String, event: String) {
        info("📱 $activityName -> $event")
    }

    fun logCommandReceived(command: String, origin: String) {
        info("📩 Command received [origin=$origin]: $command")
    }

    fun logCommandProcessing(command: String, origin: String) {
        debug("⚙️ Processing command [origin=$origin]: $command")
    }

    fun logCommandSuccess(command: String, origin: String, detail: String? = null) {
        info("✅ Command executed successfully [origin=$origin]: $command" + (detail?.let { " ($it)" } ?: ""))
    }

    fun logCommandFailure(command: String, origin: String, error: String, throwable: Throwable? = null) {
        error("❌ Command failed [origin=$origin]: $command -> $error", throwable)
    }

    fun logFileUpload(fileName: String, success: Boolean, error: String? = null, origin: String = "user") {
        if (success) {
            info("📤 File uploaded successfully [origin=$origin]: $fileName")
        } else {
            error("📤 File upload failed [origin=$origin]: $fileName -> ${error ?: "Unknown error"}")
        }
    }

    fun logWebSocketConnection(connected: Boolean, detail: String? = null) {
        if (connected) {
            info("🔌 WebSocket connected" + (detail?.let { " ($it)" } ?: ""))
        } else {
            // ডিসকানেক্ট লগও থ্রটল হবে, কিন্তু আমরা চাই শুধু স্টেট চেঞ্জ হলে লগ হোক
            // তাই এখানে WARN ব্যবহার করছি, যা থ্রটল ফিল্টার দিয়ে যায়
            warn("🔌 WebSocket disconnected" + (detail?.let { " ($it)" } ?: ""))
        }
    }

    fun logLocation(lat: Double, lon: Double, success: Boolean, error: String? = null, origin: String = "user") {
        if (success) {
            info("📍 Location captured & uploaded [origin=$origin]: ($lat, $lon)")
        } else {
            error("📍 Location failed [origin=$origin]: ${error ?: "Unknown error"}")
        }
    }

    fun logNotificationForward(app: String, title: String, success: Boolean, error: String? = null) {
        if (success) {
            info("🔔 Notification forwarded: $app -> $title")
        } else {
            error("🔔 Notification forward failed: $app -> $title : ${error ?: "Unknown"}")
        }
    }

    fun logMediaProjection(type: String, granted: Boolean, error: String? = null) {
        if (granted) {
            info("📺 MediaProjection granted for $type")
        } else {
            error("📺 MediaProjection denied for $type" + (error?.let { ": $it" } ?: ""))
        }
    }

    fun logPermission(permission: String, granted: Boolean) {
        if (granted) {
            info("🔓 Permission granted: $permission")
        } else {
            warn("🔒 Permission denied: $permission")
        }
    }

    private fun setupUncaughtExceptionHandler() {
        defaultExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            error("💥 UNCAUGHT EXCEPTION in ${thread.name}", throwable)
            defaultExceptionHandler?.uncaughtException(thread, throwable)
        }
    }

    // ======================= ইউটিলিটি =======================
    fun clearLogs(): Boolean {
        synchronized(lock) {
            return try {
                logFile?.delete() ?: false
            } catch (e: Exception) {
                false
            }
        }
    }

    fun getLogFilePath(): String? = logFile?.absolutePath
    fun getLogFileSize(): Long = logFile?.length() ?: 0
    fun readLogs(): String? = try { logFile?.readText() } catch (e: Exception) { null }

    fun shareLogs(context: Context): android.content.Intent? {
        val file = logFile ?: return null
        if (!file.exists()) return null
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
        return android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}