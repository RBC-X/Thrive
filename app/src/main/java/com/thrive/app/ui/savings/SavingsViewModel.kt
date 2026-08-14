package com.thrive.app.ui.savings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thrive.app.ThriveApp
import com.thrive.app.data.ThriveRepository
import com.thrive.app.data.model.Coupon
import com.thrive.app.data.remote.BackupCode
import com.thrive.app.data.remote.BackupSnapshot
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

data class SavingsUiState(
    val coupons: List<Coupon> = emptyList(),
    val category: String = "All",
    val query: String = "",
    val favorites: Set<String> = emptySet(),
    val sync: SyncState = SyncState(),
    val backupCode: String = "",
    val backupMsg: String? = null,
) {
    val categories: List<String>
        get() {
            // Stable order so Tech and every category always appear as chips,
            // even when a filter hides all of one category's coupons.
            val canonical = listOf(
                "All", "Grocery", "Dining", "Essentials", "Beauty", "Health", "Home", "Travel", "Tech",
            )
            val present = coupons.map { it.category }.distinct()
            return canonical.filter { it == "All" || it in present } +
                present.filterNot { it in canonical }
        }

    val filtered: List<Coupon>
        get() = coupons.filter { c ->
            (category == "All" || c.category == category) &&
                (query.isBlank() ||
                    c.title.contains(query, ignoreCase = true) ||
                    c.store.contains(query, ignoreCase = true))
        }

    /** Deterministic "deal of the day" so the feed always has a hero. */
    val dailyPick: Coupon?
        get() {
            if (coupons.isEmpty()) return null
            val index = (Calendar.getInstance().get(Calendar.DAY_OF_YEAR)) % coupons.size
            return coupons[index]
        }

    val totalPotentialSavings: Double
        get() = filtered.filter { it.priceBefore > it.priceAfter }
            .sumOf { it.priceBefore - it.priceAfter }
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
        // merge them in. Silent on failure — favorites stay local when offline.
        viewModelScope.launch {
            val remote = backup.pull(backup.activeCode()).favorites
            val local = repo.favoriteCouponIds()
            val merged = local + remote
            if (merged != local) {
                repo.saveCouponFavorites(merged) // add-only merge: never un-favorites
                _state.update { s -> s.copy(favorites = merged) }
            }
            if (merged.isNotEmpty() && merged != remote) backup.pushFavorites(merged)
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

    fun toggleFavorite(id: String) {
        viewModelScope.launch {
            val nowFavorite = repo.toggleCouponFavorite(id)
            _state.update { s ->
                val favs = s.favorites.toMutableSet()
                if (nowFavorite) favs.add(id) else favs.remove(id)
                s.copy(favorites = favs)
            }
            // Debounced push so tapping a few hearts in a row is one upload.
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
            val ok = runCatching { backup.pushAll(snapshot) }.getOrDefault(false)
            _state.update {
                it.copy(
                    backupMsg = if (ok) "Saved · code ${backup.activeCode()}" else "Backup failed — check your sync server.",
                )
            }
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
            val merged = runCatching { backup.restore(cleaned, local) }.getOrNull()
            if (merged != null) {
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
            } else {
                _state.update { it.copy(backupMsg = "Couldn't reach the backup server.") }
            }
        }
    }
}
