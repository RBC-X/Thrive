package com.thrive.app.ui.budget

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thrive.app.ai.AiAdvisor
import com.thrive.app.ai.AiService
import com.thrive.app.ai.DealFinderEngine
import com.thrive.app.ai.TripPlan
import com.thrive.app.data.ThriveRepository
import com.thrive.app.data.model.BudgetState
import com.thrive.app.data.model.ShoppingItem
import com.thrive.app.data.remote.BackupMerge
import com.thrive.app.data.remote.StateBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BudgetUiState(
    val budget: Double = 0.0,
    val people: Int = 1,
    val items: List<ShoppingItem> = emptyList(),
    val plan: TripPlan? = null,
    val isPlanning: Boolean = false,
    val aiEnabled: Boolean = false,
) {
    val hasBudget: Boolean get() = budget > 0
    val currentTotal: Double get() = items.sumOf { it.estPrice * it.quantity }
    val itemsCount: Int get() = items.size
}

class BudgetViewModel(app: Application, private val repo: ThriveRepository) : AndroidViewModel(app) {

    private val engine = DealFinderEngine
    private val ai = AiService((app as com.thrive.app.ThriveApp).settings)
    private val advisor = AiAdvisor(ai)

    val catalog: List<com.thrive.app.data.model.CatalogItem> by lazy { repo.catalog }

    private val _state = MutableStateFlow(repo.loadBudget().let { s ->
        BudgetUiState(budget = s.budget, people = if (s.people <= 0) 1 else s.people, items = s.items)
    })
    val state: StateFlow<BudgetUiState> = _state.asStateFlow()

    // Anonymous backup: budget + shopping list sync under the backup code.
    private val backup = StateBackup((app as com.thrive.app.ThriveApp).settings) { repo.syncBaseUrl }
    private var pushJob: Job? = null

    init {
        _state.update { it.copy(aiEnabled = ai.isEnabled) }
        // Non-blocking: pull budget saved under this device's backup code and
        // merge add-only, so the list survives reinstalls. Silent when offline.
        viewModelScope.launch {
            val remote = backup.pull(backup.activeCode()).budget
            if (remote != null) {
                val local = repo.loadBudget()
                val merged = BackupMerge.budget(local, remote)
                if (merged != local) {
                    repo.saveBudget(merged)
                    _state.update {
                        it.copy(budget = merged.budget, people = merged.people, items = merged.items)
                    }
                }
            }
        }
    }

    /** Debounced budget-only push so a burst of edits is one upload. */
    private fun schedulePush() {
        pushJob?.cancel()
        pushJob = viewModelScope.launch {
            delay(1_500)
            val s = _state.value
            runCatching {
                backup.pushBudget(BudgetState(budget = s.budget, people = s.people, items = s.items))
            }
        }
    }

    /** Applies a restored/merged budget state (from Settings restore). */
    fun applyRestored(budget: BudgetState?) {
        if (budget == null) return
        repo.saveBudget(budget)
        _state.update { it.copy(budget = budget.budget, people = budget.people, items = budget.items) }
    }

    private fun persist() {
        val s = _state.value
        repo.saveBudget(
            com.thrive.app.data.model.BudgetState(
                budget = s.budget,
                people = s.people,
                items = s.items,
            )
        )
    }

    fun setBudget(value: Double) {
        _state.update { it.copy(budget = value) }
        persist()
        schedulePush()
    }

    fun setPeople(value: Int) {
        _state.update { it.copy(people = value.coerceIn(1, 12)) }
        persist()
        schedulePush()
    }

    fun addItem(name: String, category: String, quantity: Int, unit: String, estPrice: Double) {
        val item = repo.addShoppingItem(
            ShoppingItem(
                id = "",
                name = name,
                category = category,
                quantity = quantity,
                unit = unit,
                estPrice = estPrice,
            )
        )
        _state.update { it.copy(items = it.items + item) }
        persist()
        schedulePush()
    }

    fun removeItem(id: String) {
        repo.removeShoppingItem(id)
        _state.update { it.copy(items = it.items.filterNot { i -> i.id == id }) }
        persist()
        schedulePush()
    }

    fun changeQuantity(id: String, delta: Int) {
        val current = _state.value.items.firstOrNull { it.id == id } ?: return
        val next = current.copy(quantity = (current.quantity + delta).coerceAtLeast(0))
        if (next.quantity == 0) {
            removeItem(id)
        } else {
            repo.updateShoppingItem(next)
            _state.update { it.copy(items = it.items.map { i -> if (i.id == id) next else i }) }
            schedulePush()
        }
    }

    fun toggleChecked(id: String) {
        val current = _state.value.items.firstOrNull { it.id == id } ?: return
        val next = current.copy(checked = !current.checked)
        repo.updateShoppingItem(next)
        _state.update { it.copy(items = it.items.map { i -> if (i.id == id) next else i }) }
        schedulePush()
    }

    fun clearItems() {
        repo.clearShoppingList()
        _state.update { it.copy(items = emptyList(), plan = null) }
        schedulePush()
    }

    fun clearPlanForEdit() = _state.update { it.copy(plan = null) }

    fun findDeals() {
        _state.update { it.copy(isPlanning = true, plan = null) }
        viewModelScope.launch {
            val s = _state.value
            val plan = withContext(Dispatchers.Default) {
                engine.plan(s.items, repo.deals, s.budget, s.people)
            }
            val enriched = if (ai.isEnabled) {
                val insights = runCatching {
                    advisor.dealInsights(
                        stores = plan.storesUsed,
                        savings = plan.totalSavings,
                        isOverBudget = plan.isOverBudget,
                        swapHints = plan.swaps.take(2).map { sw -> "${sw.itemName}: ${sw.suggestion}" },
                    )
                }.getOrNull()
                plan.copy(aiInsights = insights)
            } else plan
            _state.update {
                it.copy(plan = enriched, isPlanning = false, aiEnabled = ai.isEnabled)
            }
        }
    }
}
