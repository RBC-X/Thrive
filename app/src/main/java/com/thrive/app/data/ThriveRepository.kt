package com.thrive.app.data

import android.content.Context
import com.thrive.app.data.local.AssetLoader
import com.thrive.app.data.local.SettingsStore
import com.thrive.app.data.model.BudgetState
import com.thrive.app.data.model.CatalogItem
import com.thrive.app.data.model.Coupon
import com.thrive.app.data.model.Deal
import com.thrive.app.data.model.HouseholdProfile
import com.thrive.app.data.model.PantryItem
import com.thrive.app.data.model.Recipe
import com.thrive.app.data.model.ShoppingItem
import com.thrive.app.data.remote.ApiClient
import com.thrive.app.data.remote.BackupSnapshot
import com.thrive.app.data.remote.GoogleBackup
import com.thrive.app.data.remote.HttpResult
import com.thrive.app.data.remote.SyncPayload
import com.thrive.app.data.remote.SyncState
import com.thrive.app.data.remote.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
    private val accountSyncMutex = Mutex()
    private val googleBackup by lazy { GoogleBackup(settings, baseUrlProvider = { syncBaseUrl }) }
    private var accountPushJob: Job? = null

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

    /** One shared encrypted account client for every screen and mutation. */
    fun accountBackup(): GoogleBackup = googleBackup

    fun accountSnapshot(): BackupSnapshot = BackupSnapshot(
        favorites = favoriteCouponIds(),
        recipeFavorites = favoriteRecipeIds(),
        pantry = loadPantry(),
        budget = loadBudget(),
        householdProfile = loadHouseholdProfile(),
        seenDealIds = seenDealIds(),
        feedRevision = dealFeedRevision(),
        deletedFavoriteIds = tombstones(KEY_DELETED_FAVORITES),
        deletedRecipeFavoriteIds = tombstones(KEY_DELETED_RECIPE_FAVORITES),
        deletedPantryItemIds = tombstones(KEY_DELETED_PANTRY),
        deletedShoppingItemIds = tombstones(KEY_DELETED_SHOPPING),
    )

    /** Debounced account upload used by every local state mutation. */
    fun scheduleAccountSync() {
        if (!googleBackup.isSignedIn()) return
        accountPushJob?.cancel()
        accountPushJob = scope.launch {
            delay(1_500)
            accountSyncMutex.withLock { googleBackup.push(accountSnapshot()) }
        }
    }

    suspend fun syncAccountNow() = accountSyncMutex.withLock {
        googleBackup.push(accountSnapshot())
    }

    fun restoreAccountTombstones(snapshot: BackupSnapshot) {
        mergeTombstones(KEY_DELETED_FAVORITES, snapshot.deletedFavoriteIds)
        mergeTombstones(KEY_DELETED_RECIPE_FAVORITES, snapshot.deletedRecipeFavoriteIds)
        mergeTombstones(KEY_DELETED_PANTRY, snapshot.deletedPantryItemIds)
        mergeTombstones(KEY_DELETED_SHOPPING, snapshot.deletedShoppingItemIds)
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

    private fun hasLiveCoupons(items: List<Coupon>? = remoteCoupons): Boolean =
        items?.any { it.urlVerified && !it.estimated && !it.url.isNullOrBlank() } == true

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
                                // A 304 keeps the cached feed's real provenance.
                                feedOrigin = if (hasLiveCoupons()) "live" else "bundled",
                            )
                        }
                        settings.putLong(KEY_LAST_SYNC_AT, System.currentTimeMillis())
                    }
                }
                result.code in 200..299 -> {
                    val payload = json.decodeFromString(SyncPayload.serializer(), result.body)
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
                            // Only verified, non-estimated retailer offers earn
                            // the live label. Server-delivered planning data is
                            // still bundled/estimated data.
                            feedOrigin = if (hasLiveCoupons()) "live" else "bundled",
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
                    feedOrigin = if (hasLiveCoupons()) "live" else "bundled",
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
        scheduleAccountSync()
    }

    fun addPantryItem(item: PantryItem): PantryItem {
        val withId = if (item.id.isBlank()) item.copy(id = UUID.randomUUID().toString()) else item
        removeTombstone(KEY_DELETED_PANTRY, withId.id)
        savePantry(loadPantry() + withId)
        return withId
    }

    fun removePantryItem(id: String) {
        addTombstone(KEY_DELETED_PANTRY, id)
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
        scheduleAccountSync()
    }

    fun addShoppingItem(item: ShoppingItem): ShoppingItem {
        val withId = if (item.id.isBlank()) item.copy(id = UUID.randomUUID().toString()) else item
        removeTombstone(KEY_DELETED_SHOPPING, withId.id)
        val state = loadBudget()
        saveBudget(state.copy(items = state.items + withId))
        return withId
    }

    fun removeShoppingItem(id: String) {
        addTombstone(KEY_DELETED_SHOPPING, id)
        val state = loadBudget()
        saveBudget(state.copy(items = state.items.filterNot { it.id == id }))
    }

    fun updateShoppingItem(updated: ShoppingItem) {
        val state = loadBudget()
        saveBudget(state.copy(items = state.items.map { if (it.id == updated.id) updated else it }))
    }

    fun clearShoppingList() {
        val state = loadBudget()
        mergeTombstones(KEY_DELETED_SHOPPING, state.items.map { it.id }.toSet())
        saveBudget(state.copy(items = emptyList()))
    }

    // ---- Favorites ----

    fun toggleCouponFavorite(id: String): Boolean {
        val favs = favoriteCouponIds().toMutableSet()
        if (favs.add(id)) removeTombstone(KEY_DELETED_FAVORITES, id)
        else { favs.remove(id); addTombstone(KEY_DELETED_FAVORITES, id) }
        settings.putString(KEY_FAV_COUPONS, favs.joinToString(","))
        scheduleAccountSync()
        return id in favs
    }

    /** Replaces the full coupon-favorites set (used by backup merge/restore). */
    fun saveCouponFavorites(favs: Set<String>) {
        settings.putString(KEY_FAV_COUPONS, favs.joinToString(","))
        scheduleAccountSync()
    }

    fun favoriteCouponIds(): Set<String> =
        settings.getString(KEY_FAV_COUPONS, "").orEmpty().split(",").filter { it.isNotBlank() }.toSet()

    fun toggleRecipeFavorite(id: String): Boolean {
        val favs = favoriteRecipeIds().toMutableSet()
        if (favs.add(id)) removeTombstone(KEY_DELETED_RECIPE_FAVORITES, id)
        else { favs.remove(id); addTombstone(KEY_DELETED_RECIPE_FAVORITES, id) }
        settings.putString(KEY_FAV_RECIPES, favs.joinToString(","))
        scheduleAccountSync()
        return id in favs
    }

    fun favoriteRecipeIds(): Set<String> =
        settings.getString(KEY_FAV_RECIPES, "").orEmpty().split(",").filter { it.isNotBlank() }.toSet()

    /** Replaces the full recipe-favorites set (used by account restore). */
    fun saveRecipeFavorites(favs: Set<String>) {
        settings.putString(KEY_FAV_RECIPES, favs.joinToString(","))
        scheduleAccountSync()
    }

    // ---- Household profile / onboarding ----

    fun loadHouseholdProfile(): HouseholdProfile {
        val raw = settings.getString(KEY_HOUSEHOLD_PROFILE, null) ?: return HouseholdProfile()
        return runCatching { json.decodeFromString(HouseholdProfile.serializer(), raw).normalized() }
            .getOrDefault(HouseholdProfile())
    }

    fun saveHouseholdProfile(profile: HouseholdProfile, markModified: Boolean = true) {
        val normalized = profile.normalized().let {
            if (markModified && it.isOnboardingComplete) it.copy(onboardingCompletedAt = System.currentTimeMillis()) else it
        }
        settings.putString(
            KEY_HOUSEHOLD_PROFILE,
            json.encodeToString(HouseholdProfile.serializer(), normalized),
        )
        scheduleAccountSync()
    }

    fun isOnboardingComplete(): Boolean = loadHouseholdProfile().isOnboardingComplete

    fun completeOnboarding(profile: HouseholdProfile) {
        saveHouseholdProfile(
            profile.copy(
                onboardingVersion = CURRENT_ONBOARDING_VERSION,
                onboardingCompletedAt = System.currentTimeMillis(),
            ),
        )
    }

    // ---- Deal read state ----

    /**
     * Returns only catalog ids that arrived after the persisted baseline.
     * The first catalog ever observed becomes the baseline and therefore
     * returns zero "new" items instead of labeling the whole bundled feed new.
     */
    @Synchronized
    fun unseenDealIds(currentIds: Collection<String>, feedRevision: String? = null): Set<String> {
        val clean = sanitizeDealIds(currentIds)
        val state = loadDealReadState()
        if (!state.initialized) {
            saveDealReadState(
                DealReadState(
                    initialized = true,
                    seenIds = clean.takeLast(MAX_SEEN_DEAL_IDS),
                    feedRevision = feedRevision,
                ),
            )
            return emptySet()
        }
        return clean.asSequence().filterNot { it in state.seenIds }.toCollection(linkedSetOf())
    }

    /** Persist a viewed catalog immediately; safe to call during ON_STOP. */
    @Synchronized
    fun markDealsSeen(ids: Collection<String>, feedRevision: String? = null) {
        val state = loadDealReadState()
        val merged = LinkedHashSet<String>(state.seenIds.size + ids.size).apply {
            addAll(state.seenIds)
            addAll(sanitizeDealIds(ids))
        }.toList().takeLast(MAX_SEEN_DEAL_IDS)
        saveDealReadState(
            DealReadState(
                initialized = true,
                seenIds = merged,
                feedRevision = feedRevision ?: state.feedRevision,
            ),
        )
    }

    fun seenDealIds(): Set<String> = loadDealReadState().seenIds.toSet()

    fun dealFeedRevision(): String? = loadDealReadState().feedRevision

    /** Restores account read state without allowing unbounded server data. */
    @Synchronized
    fun restoreDealReadState(ids: Collection<String>, feedRevision: String?) {
        markDealsSeen(ids, feedRevision)
    }

    private fun sanitizeDealIds(ids: Collection<String>): List<String> = ids.asSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && it.length <= 160 }
        .distinct()
        .take(MAX_SEEN_DEAL_IDS)
        .toList()

    private fun loadDealReadState(): DealReadState {
        val raw = settings.getString(KEY_DEAL_READ_STATE, null) ?: return DealReadState()
        return runCatching { json.decodeFromString(DealReadState.serializer(), raw) }
            .getOrDefault(DealReadState())
    }

    private fun saveDealReadState(state: DealReadState) {
        settings.putStringImmediate(
            KEY_DEAL_READ_STATE,
            json.encodeToString(DealReadState.serializer(), state),
        )
        scheduleAccountSync()
    }

    private fun tombstones(key: String): Set<String> =
        settings.getString(key, "").orEmpty().split(',').asSequence()
            .map(String::trim).filter { it.isNotEmpty() && it.length <= 160 }.take(MAX_TOMBSTONES).toSet()

    private fun saveTombstones(key: String, ids: Collection<String>) {
        settings.putString(key, ids.asSequence().map(String::trim)
            .filter { it.isNotEmpty() && it.length <= 160 }.distinct().take(MAX_TOMBSTONES).joinToString(","))
    }

    private fun addTombstone(key: String, id: String) = mergeTombstones(key, setOf(id))

    private fun removeTombstone(key: String, id: String) {
        val next = tombstones(key) - id
        saveTombstones(key, next)
    }

    private fun mergeTombstones(key: String, ids: Collection<String>) {
        saveTombstones(key, tombstones(key) + ids)
    }

    companion object {
        private const val KEY_PANTRY = "pantry_items"
        private const val KEY_BUDGET = "budget_state"
        private const val KEY_FAV_COUPONS = "fav_coupons"
        private const val KEY_FAV_RECIPES = "fav_recipes"
        private const val KEY_HOUSEHOLD_PROFILE = "household_profile"
        private const val KEY_DEAL_READ_STATE = "deal_read_state"
        private const val KEY_DELETED_FAVORITES = "account_deleted_favorites"
        private const val KEY_DELETED_RECIPE_FAVORITES = "account_deleted_recipe_favorites"
        private const val KEY_DELETED_PANTRY = "account_deleted_pantry"
        private const val KEY_DELETED_SHOPPING = "account_deleted_shopping"
        private const val KEY_SYNC_URL = "sync_base_url"
        private const val KEY_SYNC_ETAG = "sync_etag"
        private const val KEY_LAST_SYNC_AT = "last_sync_at"
        private const val KEY_LOC_LAT = "loc_lat"
        private const val KEY_LOC_LNG = "loc_lng"
        const val SYNC_URL_KEY = KEY_SYNC_URL
        const val CURRENT_ONBOARDING_VERSION = 1
        const val MAX_SEEN_DEAL_IDS = 10_000
        const val MAX_TOMBSTONES = 10_000

        /** Millis when the last successful sync landed (persisted). */
        fun lastSyncAt(settings: SettingsStore): Long = settings.getLong(KEY_LAST_SYNC_AT, 0L)
    }
}

@Serializable
private data class DealReadState(
    val initialized: Boolean = false,
    val seenIds: List<String> = emptyList(),
    val feedRevision: String? = null,
)

/** Bump to invalidate previously cached payloads after a format change. */
private const val SYNC_CACHE_VERSION = 2

/** On-disk shape of the sync cache: payload + its ETag + a schema version. */
@Serializable
private data class SyncCacheFile(
    val version: Int = SYNC_CACHE_VERSION,
    val etag: String? = null,
    val payload: SyncPayload = SyncPayload(),
)
