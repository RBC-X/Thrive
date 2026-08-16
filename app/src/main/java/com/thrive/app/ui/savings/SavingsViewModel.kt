package com.thrive.app.ui.savings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thrive.app.ThriveApp
import com.thrive.app.data.ThriveRepository
import com.thrive.app.data.model.Coupon
import com.thrive.app.data.remote.BackupCode
import com.thrive.app.data.remote.BackupMerge
import com.thrive.app.data.remote.BackupSnapshot
import com.thrive.app.data.remote.PullResult
import com.thrive.app.data.remote.PushResult
import com.thrive.app.data.remote.RestoreResult
import com.thrive.app.data.remote.StateBackup
import com.thrive.app.data.remote.SyncState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

/** One store's worth of deals, with its nearest-branch distance (when known). */
data class StoreSection(
    val store: String,
    val coupons: List<Coupon>,
    val distMi: Double? = null,
    val city: String? = null,
)

data class SavingsUiState(
    val coupons: List<Coupon> = emptyList(),
    val category: String = "All",
    val query: String = "",
    val mode: String = "Deals",   // "Deals" | "Stores"
    val favorites: Set<String> = emptySet(),
    val sync: SyncState = SyncState(),
    val backupCode: String = "",
    val backupMsg: String? = null,
    // Google Sign-In: the ID token from the latest Google sign-in, held only
    // in memory (never persisted) so account backups can authenticate. The
    // signed-in profile itself is persisted via GoogleAccountStore.
    val googleIdToken: String? = null,
) {
    /**
     * Offers that are genuinely on sale (a real "was" price above the current
     * price) AND carry a VERIFIED direct link to the exact product page. A
     * store search URL is NOT a product link — a coupon that only has a search
     * destination is hidden rather than shown with a fake "go to product"
     * button. This is the "direct link or don't show it" rule: diversity of
     * stores and products comes from the items that really resolve, and
     * everything else is honestly hidden (counted in [hiddenUnverified]).
     */
    val available: List<Coupon> get() = coupons.filter {
        it.urlVerified && !it.url.isNullOrBlank() && (it.priceBefore > it.priceAfter)
    }

    /** How many feed offers are hidden (no verified direct product link). */
    val hiddenUnverified: Int get() = coupons.size - available.size

    val categories: List<String>
        get() {
            // Stable order so Tech and every category always appear as chips,
            // even when a filter hides all of one category's coupons.
            val canonical = listOf(
                "All", "Grocery", "Dining", "Essentials", "Beauty", "Health", "Home", "Travel", "Tech",
            )
            val present = available.map { it.category }.distinct()
            return canonical.filter { it == "All" || it in present } +
                present.filterNot { it in canonical }
        }

    /** True when the query matches title, store, category, or brand. */
    private fun matchesQuery(c: Coupon, q: String): Boolean =
        c.title.contains(q, ignoreCase = true) ||
            c.store.contains(q, ignoreCase = true) ||
            c.category.contains(q, ignoreCase = true) ||
            (c.brand?.contains(q, ignoreCase = true) == true)

    /** How strongly a coupon answers a query (brand > title > category/store). */
    private fun queryScore(c: Coupon, q: String): Int {
        var score = 0
        if (c.title.startsWith(q, ignoreCase = true)) score += 4
        else if (c.title.contains(q, ignoreCase = true)) score += 3
        if (c.brand?.contains(q, ignoreCase = true) == true) score += 2
        if (c.category.contains(q, ignoreCase = true)) score += 1
        if (c.store.contains(q, ignoreCase = true)) score += 1
        return score
    }

    val filtered: List<Coupon>
        get() {
            val inCategory = available.filter { category == "All" || it.category == category }
            val q = query.trim()
            if (q.isBlank()) return inCategory
            return inCategory
                .filter { matchesQuery(it, q) }
                .sortedWith(
                    // Rank results by how well they answer the query, then by
                    // savings (percent, then absolute), then by urgency.
                    compareByDescending<Coupon> { queryScore(it, q) }
                        .thenByDescending { it.discountPercent }
                        .thenByDescending { it.priceBefore - it.priceAfter }
                        .thenBy { it.endsInDays }
                )
        }

    /** Every deal at each store, grouped and sorted by nearest-branch distance. */
    val storeSections: List<StoreSection>
        get() {
            // Filter-aware (category + query), so the Stores tab honors the
            // same chips and search box as the Deals tab.
            val ranked = filtered
            if (ranked.isEmpty()) return emptyList()
            val dist = sync.nearbyStores.associate { it.store to it }
            return ranked
                .groupBy { it.store }
                .map { (store, list) ->
                    val info = dist[store]
                    StoreSection(
                        store = store,
                        coupons = list.sortedWith(
                            compareByDescending<Coupon> { it.discountPercent }
                                .thenBy { it.endsInDays }
                        ),
                        distMi = info?.distMi,
                        city = info?.city,
                    )
                }
                .sortedWith(
                    // Stores with a known distance come first (nearest on top);
                    // the rest fall back to alphabetical order.
                    compareBy<StoreSection> { it.distMi ?: Double.MAX_VALUE }
                        .thenBy { it.store.lowercase() }
                )
        }

    /** Fresh "new this week" deals — falls back to soonest-expiring when none are flagged new. */
    val newThisWeek: List<Coupon>
        get() {
            val fresh = available
                .filter { it.isNew }
                .sortedWith(compareBy<Coupon> { it.endsInDays }.thenBy { it.id })
            return (if (fresh.isNotEmpty()) fresh else available.sortedBy { it.endsInDays }).take(10)
        }

    /**
     * Deterministic "deal of the day" so the feed always has a hero: the
     * strongest deal (big cut, real dollar savings, little time left, fresh)
     * rotating daily among the top three — a genuinely great offer, never a
     * random catalog index. Index is clamped so a small (or single-item)
     * catalog never throws — see [pickDailyPick].
     */
    val dailyPick: Coupon?
        get() = pickDailyPick(available, Calendar.getInstance().get(Calendar.DAY_OF_YEAR))

    /**
     * User-relevant savings claim: only deals the user actually saved (favorited)
     * count. Summing the whole catalog would imply a household saving that no
     * one can realize — never present it as an outcome.
     */
    val favoritesSavings: Pair<Double, Int>?
        get() {
            val fav = available.filter { it.id in favorites && it.priceBefore > it.priceAfter }
            if (fav.isEmpty()) return null
            return (fav.sumOf { it.priceBefore - it.priceAfter }) to fav.size
        }
}

/**
 * Pure "deal of the day" selection so the feed hero is deterministic and
 * provably safe for any catalog size: null on empty, the single item when the
 * feed has one, otherwise a member of the strongest offers rotating by day.
 */
internal fun pickDailyPick(available: List<Coupon>, day: Int): Coupon? {
    if (available.isEmpty()) return null
    fun strength(c: Coupon): Double {
        var s = c.discountPercent.toDouble()
        s += ((c.priceBefore - c.priceAfter) / 10.0).coerceAtMost(20.0)
        if (c.endsInDays <= 3) s += 18
        else if (c.endsInDays <= 7) s += 9
        if (c.isNew) s += 6
        return s
    }
    val ranked = available.sortedWith(
        compareByDescending<Coupon> { strength(it) }
            .thenBy { kotlin.math.abs((it.id + day.toString()).hashCode()) }
    )
    // Clamp so a small (or single-item) catalog never throws — the hero is the
    // strongest offer when fewer than three exist.
    return ranked[minOf(day % 3, ranked.size - 1)]
}

class SavingsViewModel(app: ThriveApp, private val repo: ThriveRepository) : ViewModel() {

    private val backup = StateBackup(app.settings) { repo.syncBaseUrl }
    private var pushJob: Job? = null

    // Emitted after a full restore so other tabs (pantry/budget) refresh their
    // on-screen state from the merged snapshot.
    private val _restored = MutableSharedFlow<BackupSnapshot>(extraBufferCapacity = 1)
    val restored: SharedFlow<BackupSnapshot> = _restored.asSharedFlow()

    private val _state = MutableStateFlow(
        SavingsUiState(coupons = repo.coupons, backupCode = backup.activeCode())
    )
    val state: StateFlow<SavingsUiState> = _state.asStateFlow()

    init {
        _state.update { it.copy(favorites = repo.favoriteCouponIds(), sync = repo.syncState.value) }
        // Non-blocking: pull favorites saved under this device's backup code and
        // merge them in. Only a confirmed server answer merges; failures are
        // silent here (the Settings card surfaces backup availability).
        viewModelScope.launch {
            when (val result = backup.pull(backup.activeCode())) {
                is PullResult.Found -> {
                    val local = repo.favoriteCouponIds()
                    val merged = local + result.snapshot.favorites
                    if (merged != local) {
                        repo.saveCouponFavorites(merged) // add-only merge: never un-favorites
                        _state.update { s -> s.copy(favorites = merged) }
                    }
                    if (merged.isNotEmpty() && merged != result.snapshot.favorites) {
                        backup.pushFavorites(merged)
                    }
                }
                else -> { /* offline/unreachable/insecure — keep local favorites */ }
            }
        }
        // Follow sync progress so the feed header can show live/offline status
        // and the coupon list swaps to the remote feed when it arrives.
        viewModelScope.launch {
            repo.syncState.collect { s ->
                _state.update {
                    it.copy(
                        sync = s,
                        coupons = if (s.status == com.thrive.app.data.remote.SyncStatus.OK) repo.coupons else it.coupons,
                    )
                }
            }
        }
    }

    fun refreshNow() {
        viewModelScope.launch {
            repo.syncNow(force = true)
            _state.update { it.copy(coupons = repo.coupons, favorites = repo.favoriteCouponIds()) }
        }
    }

    fun selectCategory(category: String) = _state.update { it.copy(category = category) }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun setMode(mode: String) = _state.update { it.copy(mode = mode) }

    fun toggleFavorite(id: String) {
        viewModelScope.launch {
            val nowFavorite = repo.toggleCouponFavorite(id)
            _state.update { s ->
                val favs = s.favorites.toMutableSet()
                if (nowFavorite) favs.add(id) else favs.remove(id)
                s.copy(favorites = favs)
            }
            // Debounced push so tapping a few hearts in a row is one upload.
            // The push itself re-pulls/merges on conflict; failures just mean
            // the server hasn't seen the change yet (retried next time).
            pushJob?.cancel()
            pushJob = viewModelScope.launch {
                delay(1_500)
                runCatching { backup.pushFavorites(_state.value.favorites) }
                _state.update { it.copy(backupMsg = null) }
            }
        }
    }

    /** Status line set from the Settings screen (e.g. "code copied"). */
    fun setBackupMsg(msg: String) = _state.update { it.copy(backupMsg = msg) }

    /** Manual "Back up now" from Settings — pushes every section at once. */
    fun backupNow() {
        viewModelScope.launch {
            _state.update { it.copy(backupMsg = "Backing up…") }
            val snapshot = BackupSnapshot(
                favorites = repo.favoriteCouponIds(),
                pantry = repo.loadPantry(),
                budget = repo.loadBudget(),
            )
            val result = runCatching { backup.pushAll(snapshot) }.getOrDefault(PushResult.NetworkFailure)
            val msg = when (result) {
                is PushResult.Ok -> "Saved · code ${backup.activeCode()}"
                is PushResult.NotPermitted ->
                    "Backup is off on this connection — use a secure (HTTPS) sync server."
                is PushResult.Unauthorized -> "Backup server rejected the request (unauthorized)."
                is PushResult.NetworkFailure -> "Couldn't reach the backup server — check your connection."
                is PushResult.HttpError -> "Backup server error (${result.code})."
                else -> "Backup failed — nothing was changed."
            }
            _state.update { it.copy(backupMsg = msg) }
        }
    }

    /**
     * Restores + merges the full snapshot (saved deals, pantry, budget) from
     * another device's code, adopts it, pushes the union back, and publishes
     * the merged snapshot so every tab refreshes.
     */
    fun restoreBackup(code: String) {
        viewModelScope.launch {
            val cleaned = code.trim().lowercase()
            if (!BackupCode.isValid(cleaned)) {
                _state.update { it.copy(backupMsg = "That doesn't look like a backup code (6-12 letters/numbers).") }
                return@launch
            }
            _state.update { it.copy(backupMsg = "Restoring…") }
            val local = BackupSnapshot(
                favorites = repo.favoriteCouponIds(),
                pantry = repo.loadPantry(),
                budget = repo.loadBudget(),
            )
            when (val result = runCatching { backup.restore(cleaned, local) }.getOrElse {
                RestoreResult.Failed("Couldn't reach the backup server.")
            }) {
                is RestoreResult.Restored -> {
                    val merged = result.snapshot
                    repo.saveCouponFavorites(merged.favorites)
                    repo.savePantry(merged.pantry)
                    merged.budget?.let { repo.saveBudget(it) }
                    _state.update {
                        it.copy(
                            favorites = merged.favorites,
                            backupCode = backup.activeCode(),
                            backupMsg = "Restored ${merged.favorites.size} saved deals, ${merged.pantry.size} pantry items, " +
                                "and ${merged.budget?.items?.size ?: 0} shopping items.",
                        )
                    }
                    _restored.tryEmit(merged)
                }
                is RestoreResult.Failed -> {
                    _state.update { it.copy(backupMsg = result.reason) }
                }
            }
        }
    }

    // ---- Google Sign-In backup ----

    private val googleBackup = com.thrive.app.data.remote.GoogleBackup(app.settings) { repo.syncBaseUrl }

    /** Signed-in Google account, or null. Exposed so Settings can show who's signed in. */
    fun googleAccount(): com.thrive.app.data.remote.GoogleAccountInfo = googleBackup.account()

    /** True when the build is configured for Google Sign-In AND the user signed in. */
    fun googleSignedIn(): Boolean = googleBackup.isSignedIn()

    fun googleSignOut() {
        googleBackup.signOut()
        _state.update { it.copy(backupMsg = "Signed out of Google backup — your saved data stays on the server.") }
    }

    /**
     * Completes a Google sign-in: exchanges the ID token with the backend for
     * the account identity, pulls the account's saved state, merges it
     * add-only with this device, and pushes the union back. Signing into the
     * same Google account on another device brings everything with it.
     */
    fun googleCompleteSignIn(idToken: String) {
        viewModelScope.launch {
            _state.update { it.copy(backupMsg = "Signing in…", googleIdToken = idToken) }
            when (val exchanged = runCatching { googleBackup.exchange(idToken) }.getOrElse {
                com.thrive.app.data.remote.GoogleAuthResult.Failed("Couldn't reach the backup server — check your connection.")
            }) {
                is com.thrive.app.data.remote.GoogleAuthResult.Ok -> {
                    val remote = runCatching { googleBackup.pull(idToken) }.getOrDefault(
                        com.thrive.app.data.remote.PullResult.NetworkFailure
                    )
                    val local = BackupSnapshot(
                        favorites = repo.favoriteCouponIds(),
                        pantry = repo.loadPantry(),
                        budget = repo.loadBudget(),
                    )
                    val merged = when (remote) {
                        is PullResult.Found -> BackupSnapshot(
                            favorites = local.favorites + remote.snapshot.favorites,
                            pantry = BackupMerge.pantry(local.pantry, remote.snapshot.pantry),
                            budget = when {
                                local.budget == null && remote.snapshot.budget == null -> null
                                local.budget == null -> remote.snapshot.budget
                                remote.snapshot.budget == null -> local.budget
                                else -> BackupMerge.budget(local.budget, remote.snapshot.budget)
                            },
                        )
                        else -> local
                    }
                    // Persist the merged state locally, then push the union so
                    // the server has everything from every signed-in device.
                    repo.saveCouponFavorites(merged.favorites)
                    repo.savePantry(merged.pantry)
                    merged.budget?.let { repo.saveBudget(it) }
                    _state.update {
                        it.copy(
                            favorites = merged.favorites,
                            backupMsg = "Signed in as ${exchanged.account.name.ifBlank { exchanged.account.email }}.",
                        )
                    }
                    _restored.tryEmit(merged)
                    runCatching { googleBackup.push(idToken, merged) }
                }
                is com.thrive.app.data.remote.GoogleAuthResult.Failed -> {
                    _state.update { it.copy(backupMsg = exchanged.reason) }
                }
            }
        }
    }

    /** Manual "Back up now" when signed in with Google — pushes every section. */
    fun googleBackupNow() {
        viewModelScope.launch {
            val idToken = _state.value.googleIdToken ?: return@launch
            _state.update { it.copy(backupMsg = "Backing up…") }
            val snapshot = BackupSnapshot(
                favorites = repo.favoriteCouponIds(),
                pantry = repo.loadPantry(),
                budget = repo.loadBudget(),
            )
            when (val result = runCatching { googleBackup.push(idToken, snapshot) }.getOrDefault(PushResult.NetworkFailure)) {
                is PushResult.Ok -> _state.update { it.copy(backupMsg = "Saved to your Google account.") }
                is PushResult.Unauthorized -> _state.update { it.copy(backupMsg = "Google session expired — sign in again.") }
                is PushResult.NetworkFailure -> _state.update { it.copy(backupMsg = "Couldn't reach the backup server — check your connection.") }
                is PushResult.HttpError -> _state.update { it.copy(backupMsg = "Backup server error (${result.code}).") }
                else -> _state.update { it.copy(backupMsg = "Backup failed — nothing was changed.") }
            }
        }
    }
}
