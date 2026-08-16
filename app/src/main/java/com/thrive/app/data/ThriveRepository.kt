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
import com.thrive.app.data.remote.HttpResult
import com.thrive.app.data.remote.SyncPayload
import com.thrive.app.data.remote.SyncState
import com.thrive.app.data.remote.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/** Network boundary for the sync feed — swapped for a fake in repository tests. */
fun interface SyncFetcher {
    suspend fun get(url: String, ifNoneMatch: String?): HttpResult
}

/** Real implementation of [SyncFetcher] backed by [ApiClient]. */
object ApiClientFetcher : SyncFetcher {
    override suspend fun get(url: String, ifNoneMatch: String?): HttpResult =
        ApiClient.get(url, ifNoneMatch)
}

/** Single source of truth for bundled content, remote sync, and user state. */
class ThriveRepository(
    context: Context,
    private val settings: SettingsStore,
    private val fetcher: SyncFetcher = ApiClientFetcher,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val bundledCoupons: List<Coupon> by lazy { AssetLoader.coupons(appContext) }
    private val bundledRecipes: List<Recipe> by lazy { AssetLoader.recipes(appContext) }
    private val bundledDeals: List<Deal> by lazy { AssetLoader.deals(appContext) }
    private val bundledCatalog: List<CatalogItem> by lazy { AssetLoader.catalog(appContext) }

    // Remote data replaces the bundled feed after a successful sync; the
    // bundled datasets remain the offline fallback until then (and forever if
    // no server is configured/reachable). The last-good live feed is persisted
    // ATOMICALLY alongside its ETag and a cache schema version, so a process
    // restart (or a 304 "nothing changed" answer) keeps showing the live
    // catalog instead of silently falling back to bundled — and a persisted
    // ETag is never sent before the payload it belongs to is restored.
    private val cacheFile = java.io.File(appContext.filesDir, "sync_payload.json")

    @Volatile private var remoteCoupons: List<Coupon>? = null
    @Volatile private var remoteRecipes: List<Recipe>? = null
    @Volatile private var remoteDeals: List<Deal>? = null
    @Volatile private var remoteCatalog: List<CatalogItem>? = null

    // Sync is serialized so rapid refreshes and simultaneous initial/manual
    // syncs can't interleave reads/writes or double-fire the 304 retry.
    private val syncMutex = Mutex()

    private var hydrationJob: Job? = null

    init {
        // Hydrate the persisted live feed off the main thread so startup never
        // blocks on reading/parsing the cached payload.
        hydrationJob = scope.launch { restoreCachedPayload() }
    }

    /** Test hook: wait until the persisted cache has been restored. */
    internal suspend fun awaitHydration() {
        hydrationJob?.join()
    }

    /**
     * Loads the last successfully-synced payload from disk. The cached ETag
     * travels with the payload so they can never disagree; a corrupt,
     * truncated, or schema-incompatible cache is deleted and the ETag cleared
     * so the next sync does an unconditional refresh instead of dead-ending
     * on a 304 with nothing to show.
     */
    private fun restoreCachedPayload() {
        try {
            if (!cacheFile.exists()) return
            val cached = json.decodeFromString(SyncCacheFile.serializer(), cacheFile.readText())
            if (cached.version != SYNC_CACHE_VERSION) {
                cacheFile.delete()
                settings.remove(KEY_SYNC_ETAG)
                return
            }
            cached.etag?.let { settings.putString(KEY_SYNC_ETAG, it) }
            if (cached.payload.coupons.isNotEmpty()) remoteCoupons = cached.payload.coupons
            if (cached.payload.recipes.isNotEmpty()) remoteRecipes = cached.payload.recipes
            if (cached.payload.deals.isNotEmpty()) remoteDeals = cached.payload.deals
            if (cached.payload.catalog.isNotEmpty()) remoteCatalog = cached.payload.catalog
        } catch (_: Exception) {
            // Corrupt cache — never block startup. Drop the payload and its
            // ETag so the next sync fetches unconditionally.
            cacheFile.delete()
            settings.remove(KEY_SYNC_ETAG)
        }
    }

    /** Persists the last-good live payload + ETag atomically (temp + rename). */
    private fun cachePayload(payload: SyncPayload, etag: String?) {
        try {
            val parent = cacheFile.parentFile
            val tmp = java.io.File(parent, cacheFile.name + ".tmp")
            val encoded = json.encodeToString(
                SyncCacheFile.serializer(),
                SyncCacheFile(SYNC_CACHE_VERSION, etag, payload),
            )
            tmp.writeText(encoded)
            if (!tmp.renameTo(cacheFile)) {
                tmp.delete()
                cacheFile.writeText(encoded) // non-atomic last resort (e.g. locked dir)
            }
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
     * design: any failure leaves the bundled/previous data in place. Serialized
     * by a mutex so concurrent calls can't interleave.
     */
    suspend fun syncNow(force: Boolean = false) {
        syncMutex.withLock { doSync(force) }
    }

    private suspend fun doSync(force: Boolean) {
        // Never send a persisted ETag before the payload it belongs to has been
        // restored — otherwise a 304 on a cold start dead-ends on bundled data.
        awaitHydration()

        _syncState.update { it.copy(status = SyncStatus.SYNCING, error = null) }
        val base = syncBaseUrl
        if (base.isBlank()) {
            _syncState.update {
                it.copy(status = SyncStatus.OFFLINE, feedOrigin = "bundled")
            }
            return
        }
        runCatching {
            var result = fetch(base, force)
            if (result.code == 304 && remoteCoupons == null) {
                // Cold start with a persisted ETag but no usable cached payload:
                // the server says nothing changed, but we have nothing to show.
                // Retry once WITHOUT If-None-Match so the live feed loads.
                result = fetch(base, force = true)
            }
            when {
                result.code == 304 -> {
                    if (remoteCoupons == null) {
                        // Server kept answering 304 with no usable local payload
                        // — the conditional request cannot be satisfied. Show
                        // bundled data honestly labeled, and surface the state.
                        _syncState.update {
                            it.copy(
                                status = SyncStatus.ERROR,
                                error = "Server reported no changes but no cached feed exists",
                                feedOrigin = "bundled",
                            )
                        }
                    } else {
                        // Nothing changed server-side; keep current data.
                        _syncState.update {
                            it.copy(
                                status = SyncStatus.OK,
                                lastSyncedAt = System.currentTimeMillis(),
                                // Keep whatever origin the current coupons came
                                // from: 304 means nothing changed, so the
                                // on-screen set is still live if it was live.
                                feedOrigin = "live",
                            )
                        }
                        settings.putLong(KEY_LAST_SYNC_AT, System.currentTimeMillis())
                    }
                }
                result.code in 200..299 -> {
                    val payload = json.decodeFromString(SyncPayload.serializer(), result.body)
                    val couponsFromServer = payload.coupons.isNotEmpty()
                    remoteCoupons = payload.coupons.ifEmpty { remoteCoupons }
                    remoteRecipes = payload.recipes.ifEmpty { remoteRecipes }
                    remoteDeals = payload.deals.ifEmpty { remoteDeals }
                    remoteCatalog = payload.catalog.ifEmpty { remoteCatalog }
                    val etag = result.etag
                    etag?.let { settings.putString(KEY_SYNC_ETAG, it) }
                    cachePayload(payload, etag ?: settings.getString(KEY_SYNC_ETAG, null))
                    settings.putLong(KEY_LAST_SYNC_AT, System.currentTimeMillis())
                    _syncState.update {
                        it.copy(
                            status = SyncStatus.OK,
                            lastSyncedAt = System.currentTimeMillis(),
                            source = payload.source,
                            update = payload.update,
                            // Label what's actually on screen: live when the
                            // server sent coupons OR we kept last-good live
                            // data; bundled otherwise.
                            feedOrigin = if (couponsFromServer || remoteCoupons != null) "live" else "bundled",
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

    private suspend fun fetch(base: String, force: Boolean): HttpResult {
        val etag = settings.getString(KEY_SYNC_ETAG, null)
        val loc = sharedLocation
        val url = if (loc != null) {
            // Round to ~6 decimals (~0.1 m) — plenty for store distance, keeps
            // URLs short and the server-side location cache bucketing stable.
            "$base/api/v1/sync?lat=${(Math.round(loc.first * 1e6) / 1e6)}&lng=${(Math.round(loc.second * 1e6) / 1e6)}"
        } else {
            "$base/api/v1/sync"
        }
        return fetcher.get(url, if (force) null else etag)
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
        private const val KEY_LAST_SYNC_AT = "last_sync_at"
        private const val KEY_LOC_LAT = "loc_lat"
        private const val KEY_LOC_LNG = "loc_lng"
        const val SYNC_URL_KEY = KEY_SYNC_URL

        /** Millis when the last successful sync landed (persisted). */
        fun lastSyncAt(settings: SettingsStore): Long = settings.getLong(KEY_LAST_SYNC_AT, 0L)
    }
}

/** Bump to invalidate previously cached payloads after a format change. */
private const val SYNC_CACHE_VERSION = 2

/** On-disk shape of the sync cache: payload + its ETag + a schema version. */
@Serializable
private data class SyncCacheFile(
    val version: Int = SYNC_CACHE_VERSION,
    val etag: String? = null,
    val payload: SyncPayload = SyncPayload(),
)
