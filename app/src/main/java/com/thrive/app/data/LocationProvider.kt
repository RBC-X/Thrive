package com.thrive.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tiny location helper built on the platform [LocationManager] — no Google Play
 * Services dependency. Coarse (city-level) accuracy is deliberately enough:
 * nearest-store distance doesn't need street precision, and coarse is a lighter
 * permission ask with an honest "approximate" label.
 *
 * Reads the newest cached fix first (instant), then falls back to actively
 * requesting one update (waits up to ~6s) so a fresh install without a cached
 * fix still gets a location.
 */
object LocationProvider {

    /** True when the app has been granted the coarse location permission. */
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** True when the user previously denied — used to route to system Settings. */
    fun wasDenied(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_DENIED

    private fun newestCachedFix(lm: LocationManager): Location? = buildList {
        runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let(::add) }
        runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let(::add) }
    }.maxByOrNull { it.time }

    /**
     * Best known approximate location (lat, lng), or null when unavailable.
     */
    suspend fun lastKnownLocation(context: Context): Pair<Double, Double>? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        newestCachedFix(lm)?.let { return it.latitude to it.longitude }

        // No cached fix: actively request a single update on the main looper and
        // wait briefly. GPS first, then network.
        return withContext(Dispatchers.Main) {
            var pending = CompletableDeferred<Location?>()
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    pending.complete(location)
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            var result: Pair<Double, Double>? = null
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (result != null) break
                pending = CompletableDeferred()
                runCatching { lm.requestSingleUpdate(provider, listener, Looper.getMainLooper()) }
                val fix = withTimeoutOrNull(6_000) { pending.await() }
                runCatching { lm.removeUpdates(listener) }
                if (fix != null) result = fix.latitude to fix.longitude
            }
            result
        }
    }

    /** Human label for the coarseness we use — keeps the permission ask honest. */
    const val ACCURACY_LABEL = "approximate location"
}
