package com.thrive.app.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.function.Consumer

/**
 * Tiny location helper built on the platform [LocationManager] — no Google Play
 * Services dependency. Coarse (city-level) accuracy is deliberately enough:
 * nearest-store distance doesn't need street precision, and coarse is a lighter
 * permission ask with an honest "approximate" label.
 *
 * Behavior:
 *  - a fresh cached fix (under 30 min old) answers instantly;
 *  - otherwise a single current fix is requested via the modern one-shot
 *    [LocationManager.getCurrentLocation] (API 30+) with a legacy
 *    `requestSingleUpdate` fallback below that — never continuous tracking;
 *  - a stale cached fix is only used as a last resort when no fresh fix is
 *    obtainable (provider off / timeout / denial), and the UI labels the
 *    result "approximate" honestly;
 *  - every request is cancellable, times out (~8s), and never blocks the main
 *    thread (all APIs are callback/one-shot based).
 */
object LocationProvider {

    private const val FRESH_CACHE_MS = 30L * 60 * 1000 // city-level fix under 30 min is current
    private const val FIX_TIMEOUT_MS = 8_000L

    /** True when the app has been granted the coarse location permission. */
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** True when the user previously denied — used to route to system Settings. */
    fun wasDenied(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_DENIED

    // Permission is checked by hasPermission() before every call site; lint can't
    // track the guard through this helper, so suppress here and keep the
    // runCatching safety net for the (already-guarded) privileged calls.
    @SuppressLint("MissingPermission")
    private fun newestCachedFix(lm: LocationManager): Location? = buildList {
        runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let(::add) }
        runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let(::add) }
    }.maxByOrNull { it.time }

    /**
     * Best known approximate location (lat, lng), or null when unavailable.
     * Permission is verified by [hasPermission] before any privileged call.
     */
    @SuppressLint("MissingPermission")
    suspend fun lastKnownLocation(context: Context): Pair<Double, Double>? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        val cached = newestCachedFix(lm)
        if (cached != null && System.currentTimeMillis() - cached.time < FRESH_CACHE_MS) {
            return cached.latitude to cached.longitude
        }

        // No fresh cached fix: request exactly one current fix, then stop.
        val fresh = requestSingleFix(context, lm)
        if (fresh != null) return fresh.latitude to fresh.longitude

        // Provider disabled / timeout / no fix yet: fall back to the newest
        // cached fix (possibly stale) so the user still gets an approximate
        // answer; the UI honestly labels it approximate.
        return cached?.let { it.latitude to it.longitude }
    }

    /** One-shot current fix from the first enabled provider that answers. */
    @SuppressLint("MissingPermission")
    private suspend fun requestSingleFix(context: Context, lm: LocationManager): Location? {
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        for (provider in providers) {
            val fix = withTimeoutOrNull(FIX_TIMEOUT_MS) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    requestCurrentFix(context, lm, provider)
                } else {
                    requestLegacyFix(lm, provider)
                }
            }
            if (fix != null) return fix
        }
        return null
    }

    /** Modern one-shot current location (API 30+). Never continuously tracks. */
    @RequiresApi(Build.VERSION_CODES.R)
    @SuppressLint("MissingPermission")
    private suspend fun requestCurrentFix(context: Context, lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val cancel = CancellationSignal()
            cont.invokeOnCancellation { cancel.cancel() }
            val consumer = Consumer<Location> { loc ->
                cancel.cancel()
                if (cont.isActive) cont.resume(loc)
            }
            runCatching {
                lm.getCurrentLocation(
                    provider,
                    cancel,
                    ContextCompat.getMainExecutor(context),
                    consumer,
                )
            }.onFailure {
                cancel.cancel()
                if (cont.isActive) cont.resume(null)
            }
        }

    /** Pre-API-30 fallback: a single legacy update request, then stop. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun requestLegacyFix(lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    runCatching { lm.removeUpdates(this) }
                    if (cont.isActive) cont.resume(location)
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

                override fun onProviderEnabled(provider: String) {}

                override fun onProviderDisabled(provider: String) {
                    runCatching { lm.removeUpdates(this) }
                    if (cont.isActive) cont.resume(null)
                }
            }
            cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
            runCatching { lm.requestSingleUpdate(provider, listener, Looper.getMainLooper()) }
                .onFailure {
                    if (cont.isActive) cont.resume(null)
                }
        }

    /** Human label for the coarseness we use — keeps the permission ask honest. */
    const val ACCURACY_LABEL = "approximate location"
}
