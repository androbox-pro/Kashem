package com.kashem.shaikh.telegram

import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File

object FileManager {

    /**
     * Delete a file or directory from the device.
     * Supports absolute paths or relative paths from the root of internal storage.
     *
     * @param path The path to the file or directory to delete.
     * @return true if deletion was successful, false otherwise.
     */
    fun deleteFile(path: String): Boolean {
        if (path.isBlank()) {
            Log.e("FileManager", "Empty path provided")
            return false
        }

        val file = getFileFromPath(path)
        if (!file.exists()) {
            Log.e("FileManager", "File does not exist: $path")
            return false
        }

        return try {
            val deleted = if (file.isDirectory) {
                deleteRecursively(file)
            } else {
                file.delete()
            }
            if (deleted) {
                Log.d("FileManager", "Successfully deleted: $path")
            } else {
                Log.e("FileManager", "Failed to delete: $path")
            }
            deleted
        } catch (e: SecurityException) {
            Log.e("FileManager", "Security exception deleting $path: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e("FileManager", "Error deleting $path: ${e.message}")
            false
        }
    }

    /**
     * Recursively delete a directory and all its contents.
     */
    private fun deleteRecursively(directory: File): Boolean {
        var success = true
        directory.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                if (!deleteRecursively(file)) {
                    success = false
                }
            } else {
                if (!file.delete()) {
                    success = false
                    Log.e("FileManager", "Failed to delete file: ${file.absolutePath}")
                }
            }
        }
        if (!directory.delete()) {
            success = false
            Log.e("FileManager", "Failed to delete directory: ${directory.absolutePath}")
        }
        return success
    }

    /**
     * Convert a path string to a File object.
     * Supports:
     * - Absolute paths (e.g., /storage/emulated/0/DCIM/photo.jpg)
     * - Relative paths from internal storage root (e.g., DCIM/photo.jpg)
     */
    private fun getFileFromPath(path: String): File {
        return if (path.startsWith("/")) {
            // Absolute path
            File(path)
        } else {
            // Relative path - resolve from internal storage root
            val baseDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // For Android 11+, use Environment.getExternalStorageDirectory() which is still accessible
                // but may have restrictions. Alternative: use getExternalFilesDir(null)?.parentFile
                Environment.getExternalStorageDirectory()
            } else {
                Environment.getExternalStorageDirectory()
            }
            File(baseDir, path)
        }
    }

    /**
     * Check if a file exists at the given path.
     */
    fun fileExists(path: String): Boolean {
        if (path.isBlank()) return false
        return getFileFromPath(path).exists()
    }

    /**
     * Get file size in bytes, or -1 if file doesn't exist.
     */
    fun getFileSize(path: String): Long {
        if (path.isBlank()) return -1
        val file = getFileFromPath(path)
        return if (file.exists() && file.isFile) file.length() else -1
    }
}