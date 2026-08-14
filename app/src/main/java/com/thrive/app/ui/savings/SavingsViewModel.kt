package com.thrive.app.ui.savings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thrive.app.data.ThriveRepository
import com.thrive.app.data.model.Coupon
import com.thrive.app.data.remote.SyncState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

class SavingsViewModel(private val repo: ThriveRepository) : ViewModel() {

    private val _state = MutableStateFlow(SavingsUiState(coupons = repo.coupons))
    val state: StateFlow<SavingsUiState> = _state.asStateFlow()

    init {
        _state.update { it.copy(favorites = repo.favoriteCouponIds(), sync = repo.syncState.value) }
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
        }
    }
}
