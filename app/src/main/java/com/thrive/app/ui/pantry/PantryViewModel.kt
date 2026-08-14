package com.thrive.app.ui.pantry

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thrive.app.ai.AiAdvisor
import com.thrive.app.ai.AiService
import com.thrive.app.ai.MealSuggestion
import com.thrive.app.ai.PantryMealEngine
import com.thrive.app.ai.WeeklyPlan
import com.thrive.app.ai.WeeklyPlannerEngine
import com.thrive.app.data.ThriveRepository
import com.thrive.app.data.model.PantryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PantryUiState(
    val items: List<PantryItem> = emptyList(),
    val suggestions: List<MealSuggestion>? = null,
    val isLoadingMeals: Boolean = false,
    val weeklyPlan: WeeklyPlan? = null,
    val isPlanningWeek: Boolean = false,
    val aiEnabled: Boolean = false,
) {
    val expiringSoon: List<PantryItem>
        get() {
            val now = System.currentTimeMillis()
            return items.filter { it.expiresAt != null }
                .filter { (it.expiresAt!! - now) in 0 until 3L * 24 * 3600_000L }
                .sortedBy { it.expiresAt }
        }

    fun forLocation(location: String): List<PantryItem> =
        items.filter { it.location == location }.sortedBy { it.name.lowercase() }

    val totalCount: Int get() = items.size
}

class PantryViewModel(app: Application, private val repo: ThriveRepository) : AndroidViewModel(app) {

    private val engine = PantryMealEngine
    private val ai = AiService((app as com.thrive.app.ThriveApp).settings)
    private val advisor = AiAdvisor(ai)

    val catalog: List<com.thrive.app.data.model.CatalogItem> by lazy { repo.catalog }

    private val _state = MutableStateFlow(PantryUiState(items = repo.loadPantry()))
    val state: StateFlow<PantryUiState> = _state.asStateFlow()

    init {
        _state.update { it.copy(aiEnabled = ai.isEnabled) }
    }

    fun addItem(
        name: String,
        category: String,
        location: String,
        quantity: Int = 1,
        unit: String = "",
        expiresInDays: Int? = null,
    ) {
        val expiresAt = expiresInDays?.let { System.currentTimeMillis() + it * 24 * 3600_000L }
        val item = repo.addPantryItem(
            PantryItem(
                id = "",
                name = name,
                category = category,
                location = location,
                quantity = quantity,
                unit = unit,
                expiresAt = expiresAt,
                addedAt = System.currentTimeMillis(),
            )
        )
        _state.update { it.copy(items = it.items + item) }
    }

    fun removeItem(id: String) {
        repo.removePantryItem(id)
        _state.update { it.copy(items = it.items.filterNot { i -> i.id == id }) }
    }

    fun changeQuantity(id: String, delta: Int) {
        val current = _state.value.items.firstOrNull { it.id == id } ?: return
        val next = current.copy(quantity = (current.quantity + delta).coerceAtLeast(0))
        if (next.quantity == 0) {
            removeItem(id)
        } else {
            repo.updatePantryItem(next)
            _state.update { it.copy(items = it.items.map { i -> if (i.id == id) next else i }) }
        }
    }

    fun generateMeals(focus: String = "balanced") {
        _state.update { it.copy(isLoadingMeals = true, suggestions = null) }
        viewModelScope.launch {
            val items = _state.value.items
            val suggestions = withContext(Dispatchers.Default) {
                engine.suggest(items, repo.recipes, focus = focus, limit = 3)
            }
            // Enrich with AI tips when configured; failures fall back silently to local results.
            val enriched = suggestions.map { s ->
                val tip = if (ai.isEnabled) {
                    runCatching {
                        advisor.mealTip(
                            mealName = s.recipe.name,
                            usedItems = s.usedItems,
                            missingItems = s.missingItems.map { it.name },
                            budgetHint = "$" + String.format("%.0f", s.recipe.costDollars) + " total",
                        )
                    }.getOrNull()
                } else null
                s.copy(aiTip = tip)
            }
            _state.update { it.copy(suggestions = enriched, isLoadingMeals = false, aiEnabled = ai.isEnabled) }
        }
    }

    fun clearSuggestions() = _state.update { it.copy(suggestions = null) }

    // ---- Weekly planner ----

    private val planner = WeeklyPlannerEngine
    private var lastPlanParams: Triple<Int, Double, String>? = null

    fun generateWeeklyPlan(people: Int, budget: Double, focus: String = "balanced") {
        lastPlanParams = Triple(people, budget, focus)
        _state.update { it.copy(isPlanningWeek = true, weeklyPlan = null) }
        viewModelScope.launch {
            val items = _state.value.items
            val plan = withContext(Dispatchers.Default) {
                planner.plan(items, repo.recipes, nights = 7, budget = budget, people = people, focus = focus)
            }
            val enriched = if (ai.isEnabled) {
                val tip = runCatching {
                    advisor.weekTip(
                        nightCount = plan.nightsCount,
                        totalCost = plan.totalCost,
                        budget = plan.budget,
                        shoppingCount = plan.combinedShopping.size,
                        underBudget = plan.underBudget,
                    )
                }.getOrNull()
                plan.copy(aiTip = tip)
            } else plan
            _state.update { it.copy(weeklyPlan = enriched, isPlanningWeek = false, aiEnabled = ai.isEnabled) }
        }
    }

    fun rePlan() {
        lastPlanParams?.let { (people, budget, focus) -> generateWeeklyPlan(people, budget, focus) }
    }
}
