package com.thrive.app.data

import android.content.Context
import com.thrive.app.data.local.AssetLoader
import com.thrive.app.data.local.SettingsStore
import com.thrive.app.data.model.BudgetState
import com.thrive.app.data.model.CatalogItem
import com.thrive.app.data.model.Coupon
import com.thrive.app.data.model.Deal
import com.thrive.app.data.model.PantryItem
import com.thrive.app.data.model.Recipe
import com.thrive.app.data.model.ShoppingItem
import com.thrive.app.data.remote.ApiClient
import com.thrive.app.data.remote.SyncPayload
import com.thrive.app.data.remote.SyncState
import com.thrive.app.data.remote.SyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/** Single source of truth for bundled content, remote sync, and user state. */
class ThriveRepository(context: Context, private val settings: SettingsStore) {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val bundledCoupons: List<Coupon> by lazy { AssetLoader.coupons(appContext) }
    private val bundledRecipes: List<Recipe> by lazy { AssetLoader.recipes(appContext) }
    private val bundledDeals: List<Deal> by lazy { AssetLoader.deals(appContext) }
    private val bundledCatalog: List<CatalogItem> by lazy { AssetLoader.catalog(appContext) }

    // Remote data replaces the bundled feed after a successful sync; the
    // bundled datasets remain the offline fallback until then (and forever if
    // no server is configured/reachable). The last-good live feed is persisted
    // so a process restart (or a 304 "nothing changed" answer) keeps showing
    // the live catalog instead of silently falling back to bundled.
    private val cacheFile = java.io.File(appContext.filesDir, "sync_payload.json")

    @Volatile private var remoteCoupons: List<Coupon>? = null
    @Volatile private var remoteRecipes: List<Recipe>? = null
    @Volatile private var remoteDeals: List<Deal>? = null
    @Volatile private var remoteCatalog: List<CatalogItem>? = null

    init {
        restoreCachedPayload()
    }

    /** Loads the last successfully-synced payload from disk (empty cache = no-op). */
    private fun restoreCachedPayload() {
        try {
            if (!cacheFile.exists()) return
            val payload = json.decodeFromString(SyncPayload.serializer(), cacheFile.readText())
            if (payload.coupons.isNotEmpty()) remoteCoupons = payload.coupons
            if (payload.recipes.isNotEmpty()) remoteRecipes = payload.recipes
            if (payload.deals.isNotEmpty()) remoteDeals = payload.deals
            if (payload.catalog.isNotEmpty()) remoteCatalog = payload.catalog
        } catch (_: Exception) {
            cacheFile.delete() // corrupt cache — never block startup
        }
    }

    /** Persists the last-good live payload so restarts keep the live feed. */
    private fun cachePayload(payload: SyncPayload) {
        try {
            cacheFile.writeText(json.encodeToString(SyncPayload.serializer(), payload))
        } catch (_: Exception) {
            /* cache is best-effort */
        }
    }

    val coupons: List<Coupon> get() = remoteCoupons ?: bundledCoupons
    val recipes: List<Recipe> get() = remoteRecipes ?: bundledRecipes
    val deals: List<Deal> get() = remoteDeals ?: bundledDeals
    val catalog: List<CatalogItem> get() = remoteCatalog ?: bundledCatalog

    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    val syncBaseUrl: String
        get() = settings.getString(KEY_SYNC_URL, com.thrive.app.BuildConfig.DEFAULT_SYNC_URL).orEmpty().trimEnd('/')

    /** Approximate location the user opted into sharing, if any. */
    val sharedLocation: Pair<Double, Double>?
        get() {
            val lat = settings.getString(KEY_LOC_LAT, null)?.toDoubleOrNull() ?: return null
            val lng = settings.getString(KEY_LOC_LNG, null)?.toDoubleOrNull() ?: return null
            return lat to lng
        }

    /** Persist a shared approximate location and refresh the feed with it. */
    suspend fun setLocation(lat: Double, lng: Double) {
        settings.putString(KEY_LOC_LAT, lat.toString())
        settings.putString(KEY_LOC_LNG, lng.toString())
        _syncState.update {
            it.copy(locationEnabled = true, locationLat = lat, locationLng = lng)
        }
        syncNow(force = true)
    }

    /** Drop the shared location (user revoked) and refresh back to unranked. */
    suspend fun clearLocation() {
        settings.remove(KEY_LOC_LAT)
        settings.remove(KEY_LOC_LNG)
        _syncState.update {
            it.copy(locationEnabled = false, locationLat = null, locationLng = null, nearbyStores = emptyList())
        }
        syncNow(force = true)
    }

    /**
     * Pulls the latest feed from the configured Thrive sync API. Non-fatal by
     * design: any failure leaves the bundled/previous data in place.
     */
    suspend fun syncNow(force: Boolean = false) {
        _syncState.update { it.copy(status = SyncStatus.SYNCING, error = null) }
        val base = syncBaseUrl
        if (base.isBlank()) {
            _syncState.update {
                it.copy(status = SyncStatus.OFFLINE, feedOrigin = "bundled")
            }
            return
        }
        runCatching {
            val etag = settings.getString(KEY_SYNC_ETAG, null)
            val loc = sharedLocation
            val url = if (loc != null) {
                // Round to ~6 decimals (~0.1 m) — plenty for store distance, keeps
                // URLs short and the server-side location cache bucketing stable.
                "$base/api/v1/sync?lat=${(Math.round(loc.first * 1e6) / 1e6)}&lng=${(Math.round(loc.second * 1e6) / 1e6)}"
            } else {
                "$base/api/v1/sync"
            }
            val result = ApiClient.get(url, if (force) null else etag)
            when {
                result.code == 304 -> {
                    if (remoteCoupons == null) {
                        // A fresh process with no in-memory live data: the ETag
                        // says nothing changed, but we have nothing to show.
                        // Force a full fetch once so the live feed actually loads.
                        syncNow(force = true)
                        return
                    }
                    // Nothing changed server-side; keep current data.
                    _syncState.update {
                        it.copy(
                            status = SyncStatus.OK,
                            lastSyncedAt = System.currentTimeMillis(),
                            // Keep whatever origin the current coupons came from:
                            // 304 means nothing changed, so the on-screen set is
                            // still live if it was live before.
                            feedOrigin = "live",
                        )
                    }
                }
                result.code in 200..299 -> {
                    val payload = json.decodeFromString(SyncPayload.serializer(), result.body)
                    val couponsFromServer = payload.coupons.isNotEmpty()
                    remoteCoupons = payload.coupons.ifEmpty { remoteCoupons }
                    remoteRecipes = payload.recipes.ifEmpty { remoteRecipes }
                    remoteDeals = payload.deals.ifEmpty { remoteDeals }
                    remoteCatalog = payload.catalog.ifEmpty { remoteCatalog }
                    cachePayload(payload)
                    result.etag?.let { settings.putString(KEY_SYNC_ETAG, it) }
                    _syncState.update {
                        it.copy(
                            status = SyncStatus.OK,
                            lastSyncedAt = System.currentTimeMillis(),
                            source = payload.source,
                            update = payload.update,
                            feedOrigin = if (couponsFromServer) "live" else "bundled",
                            locationEnabled = payload.location != null || it.locationEnabled,
                            locationLat = payload.location?.lat ?: it.locationLat,
                            locationLng = payload.location?.lng ?: it.locationLng,
                            nearbyStores = payload.location?.nearbyStores ?: it.nearbyStores,
                        )
                    }
                }
                else -> throw IllegalStateException("sync returned ${result.code}")
            }
        }.onFailure { err ->
            _syncState.update {
                it.copy(
                    status = SyncStatus.ERROR,
                    error = err.message ?: "Sync failed",
                    feedOrigin = if (remoteCoupons != null) "live" else "bundled",
                )
            }
        }
    }

    // ---- Pantry ----

    fun loadPantry(): List<PantryItem> {
        val raw = settings.getString(KEY_PANTRY, null) ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(PantryItem.serializer()), raw) }
            .getOrDefault(emptyList())
    }

    fun savePantry(items: List<PantryItem>) {
        settings.putString(KEY_PANTRY, json.encodeToString(ListSerializer(PantryItem.serializer()), items))
    }

    fun addPantryItem(item: PantryItem): PantryItem {
        val withId = if (item.id.isBlank()) item.copy(id = UUID.randomUUID().toString()) else item
        savePantry(loadPantry() + withId)
        return withId
    }

    fun removePantryItem(id: String) {
        savePantry(loadPantry().filterNot { it.id == id })
    }

    fun updatePantryItem(updated: PantryItem) {
        savePantry(loadPantry().map { if (it.id == updated.id) updated else it })
    }

    // ---- Budget / shopping ----

    fun loadBudget(): BudgetState {
        val raw = settings.getString(KEY_BUDGET, null) ?: return BudgetState()
        return runCatching { json.decodeFromString(BudgetState.serializer(), raw) }
            .getOrDefault(BudgetState())
    }

    fun saveBudget(state: BudgetState) {
        settings.putString(KEY_BUDGET, json.encodeToString(BudgetState.serializer(), state))
    }

    fun addShoppingItem(item: ShoppingItem): ShoppingItem {
        val withId = if (item.id.isBlank()) item.copy(id = UUID.randomUUID().toString()) else item
        val state = loadBudget()
        saveBudget(state.copy(items = state.items + withId))
        return withId
    }

    fun removeShoppingItem(id: String) {
        val state = loadBudget()
        saveBudget(state.copy(items = state.items.filterNot { it.id == id }))
    }

    fun updateShoppingItem(updated: ShoppingItem) {
        val state = loadBudget()
        saveBudget(state.copy(items = state.items.map { if (it.id == updated.id) updated else it }))
    }

    fun clearShoppingList() {
        val state = loadBudget()
        saveBudget(state.copy(items = emptyList()))
    }

    // ---- Favorites ----

    fun toggleCouponFavorite(id: String): Boolean {
        val favs = favoriteCouponIds().toMutableSet()
        if (!favs.add(id)) favs.remove(id)
        settings.putString(KEY_FAV_COUPONS, favs.joinToString(","))
        return id in favs
    }

    /** Replaces the full coupon-favorites set (used by backup merge/restore). */
    fun saveCouponFavorites(favs: Set<String>) {
        settings.putString(KEY_FAV_COUPONS, favs.joinToString(","))
    }

    fun favoriteCouponIds(): Set<String> =
        settings.getString(KEY_FAV_COUPONS, "").orEmpty().split(",").filter { it.isNotBlank() }.toSet()

    fun toggleRecipeFavorite(id: String): Boolean {
        val favs = favoriteRecipeIds().toMutableSet()
        if (!favs.add(id)) favs.remove(id)
        settings.putString(KEY_FAV_RECIPES, favs.joinToString(","))
        return id in favs
    }

    fun favoriteRecipeIds(): Set<String> =
        settings.getString(KEY_FAV_RECIPES, "").orEmpty().split(",").filter { it.isNotBlank() }.toSet()

    companion object {
        private const val KEY_PANTRY = "pantry_items"
        private const val KEY_BUDGET = "budget_state"
        private const val KEY_FAV_COUPONS = "fav_coupons"
        private const val KEY_FAV_RECIPES = "fav_recipes"
        private const val KEY_SYNC_URL = "sync_base_url"
        private const val KEY_SYNC_ETAG = "sync_etag"
        private const val KEY_LOC_LAT = "loc_lat"
        private const val KEY_LOC_LNG = "loc_lng"
        const val SYNC_URL_KEY = KEY_SYNC_URL
    }
}
