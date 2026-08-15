package com.thrive.app.data.remote

import com.thrive.app.data.model.CatalogItem
import com.thrive.app.data.model.Coupon
import com.thrive.app.data.model.Deal
import com.thrive.app.data.model.Recipe
import kotlinx.serialization.Serializable

/** Full payload served by the Thrive sync API. */
@Serializable
data class SyncPayload(
    val version: Int = 0,
    val generatedAt: String = "",
    val source: List<String> = emptyList(),
    val deals: List<Deal> = emptyList(),
    val coupons: List<Coupon> = emptyList(),
    val recipes: List<Recipe> = emptyList(),
    val catalog: List<CatalogItem> = emptyList(),
    val update: UpdateInfo? = null,
    val location: PayloadLocation? = null,
)

/** Echo of the user's shared location + nearby store chains (sync with lat/lng). */
@Serializable
data class PayloadLocation(
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val nearbyStores: List<NearbyStore> = emptyList(),
)

@Serializable
data class NearbyStore(
    val store: String = "",
    val city: String = "",
    val distMi: Double = 0.0,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
)

/** Latest release advertised by the sync server (in-app update card). */
@Serializable
data class UpdateInfo(
    val versionName: String = "",
    val apkUrl: String = "",
    val notes: List<String> = emptyList(),
    val apkSizeBytes: Long = 0L,
)

/** Connectivity status of the sync layer. */
enum class SyncStatus { OFFLINE, SYNCING, OK, ERROR }

data class SyncState(
    val status: SyncStatus = SyncStatus.OFFLINE,
    val lastSyncedAt: Long? = null,
    val error: String? = null,
    val source: List<String> = emptyList(),
    val update: UpdateInfo? = null,
    /**
     * Where the coupon list actually came from on the last successful sync:
     * "live" = the server's coupon list (fresh from the feed), "bundled" = the
     * server was reachable but sent no coupons (or never connected), so the app
     * fell back to its built-in catalog. The UI uses this to be honest about
     * whether the deals shown are live or bundled estimates.
     */
    val feedOrigin: String = "bundled",
    val locationEnabled: Boolean = false, // user shared approximate location
    val locationLat: Double? = null,
    val locationLng: Double? = null,
    val nearbyStores: List<NearbyStore> = emptyList(),
) {
    val isLive: Boolean get() = status == SyncStatus.OK
    val isWorking: Boolean get() = status == SyncStatus.SYNCING
    /** True when the coupon list on screen actually came from the server. */
    val hasLiveFeed: Boolean get() = status == SyncStatus.OK && feedOrigin == "live"
    /** True when location is shared AND the feed ranked deals by distance. */
    val hasNearby: Boolean get() = locationEnabled && locationLat != null && locationLng != null
}

/** True when the server advertises a newer release than the installed build. */
fun isNewerVersion(advertised: String?, current: String): Boolean {
    if (advertised.isNullOrBlank()) return false
    val a = advertised.split('.').mapNotNull { it.toIntOrNull() }
    val b = current.split('.').mapNotNull { it.toIntOrNull() }
    val n = maxOf(a.size, b.size)
    for (i in 0 until n) {
        val av = a.getOrElse(i) { 0 }
        val bv = b.getOrElse(i) { 0 }
        if (av != bv) return av > bv
    }
    return false
}
