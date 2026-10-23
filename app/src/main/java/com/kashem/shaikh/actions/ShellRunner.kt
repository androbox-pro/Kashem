package com.kashem.shaikh.actions

import com.kashem.shaikh.services.AppLogs
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object ShellRunner {

    /**
     * Non-root shell command runner।
     * ⚠️ শুধু app-এর UID permission-এর মধ্যে থাকা commands চলবে।
     */
    fun run(cmd: String, timeoutMs: Long = 15_000L): String {
        if (cmd.isBlank()) return "ERROR: empty command"

        return try {
            AppLogs.info("ShellRunner: exec → $cmd")

            val process = ProcessBuilder("sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()

            val output = StringBuilder()
            val lock = Object()

            // ✅ FIX: Reader আলাদা thread-এ, যাতে readLine() main thread-কে block না করে
            val readerThread = Thread {
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { r ->
                        var line: String?
                        while (r.readLine().also { line = it } != null) {
                            synchronized(lock) { output.appendLine(line) }
                        }
                    }
                } catch (_: Exception) {}
            }.apply {
                isDaemon = true
                name = "ShellRunner-reader"
                start()
            }

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)

            if (!finished) {
                process.destroyForcibly()
                synchronized(lock) {
                    output.appendLine("── [TIMEOUT after ${timeoutMs}ms] ──")
                }
            } else {
                // Reader thread-কে flush করার জন্য 500ms সময় দিন
                try { readerThread.join(500) } catch (_: InterruptedException) {}
                synchronized(lock) {
                    output.appendLine("── exit=${process.exitValue()} ──")
                }
            }

            val result = synchronized(lock) { output.toString().trim() }
            AppLogs.info("ShellRunner: done (${result.length} chars)")
            result
        } catch (e: Exception) {
            AppLogs.error("ShellRunner failed: $cmd", e)
            "ERROR: ${e.message}"
        }
    }
}