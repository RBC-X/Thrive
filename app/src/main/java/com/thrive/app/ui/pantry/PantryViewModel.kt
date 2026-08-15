package com.thrive.app.ui.pantry

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thrive.app.ai.AiAdvisor
import com.thrive.app.ai.AiService
import com.thrive.app.ai.GeneratedRecipe
import com.thrive.app.ai.MealSuggestion
import com.thrive.app.ai.PantryMealEngine
import com.thrive.app.ai.RecipeMakerEngine
import com.thrive.app.ai.WeeklyPlan
import com.thrive.app.ai.WeeklyPlannerEngine
import com.thrive.app.data.ThriveRepository
import com.thrive.app.data.model.PantryItem
import com.thrive.app.data.remote.BackupMerge
import com.thrive.app.data.remote.PullResult
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

data class PantryUiState(
    val items: List<PantryItem> = emptyList(),
    val suggestions: List<MealSuggestion>? = null,
    val isLoadingMeals: Boolean = false,
    val weeklyPlan: WeeklyPlan? = null,
    val isPlanningWeek: Boolean = false,
    val generatedRecipe: GeneratedRecipe? = null,
    val isGeneratingRecipe: Boolean = false,
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

    // Anonymous backup: pantry syncs under the same backup code as favorites.
    private val backup = StateBackup((app as com.thrive.app.ThriveApp).settings) { repo.syncBaseUrl }
    private var pushJob: Job? = null

    init {
        _state.update { it.copy(aiEnabled = ai.isEnabled) }
        // Non-blocking: pull pantry saved under this device's backup code and
        // merge add-only, so pantry survives reinstalls. Only a confirmed
        // server answer merges; offline/insecure failures stay silent.
        viewModelScope.launch {
            when (val result = backup.pull(backup.activeCode())) {
                is PullResult.Found -> {
                    val local = _state.value.items
                    val merged = BackupMerge.pantry(local, result.snapshot.pantry)
                    if (merged != local) {
                        repo.savePantry(merged)
                        _state.update { it.copy(items = merged) }
                    }
                }
                else -> { /* keep local pantry */ }
            }
        }
    }

    /** Debounced pantry-only push so a burst of edits is one upload. */
    private fun schedulePush() {
        pushJob?.cancel()
        pushJob = viewModelScope.launch {
            delay(1_500)
            runCatching { backup.pushPantry(_state.value.items) }
        }
    }

    /** Applies a restored/merged pantry list (from Settings restore). */
    fun applyRestored(items: List<PantryItem>) {
        repo.savePantry(items)
        _state.update { it.copy(items = items) }
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
        schedulePush()
    }

    fun removeItem(id: String) {
        repo.removePantryItem(id)
        _state.update { it.copy(items = it.items.filterNot { i -> i.id == id }) }
        schedulePush()
    }

    fun changeQuantity(id: String, delta: Int) {
        val current = _state.value.items.firstOrNull { it.id == id } ?: return
        val next = current.copy(quantity = (current.quantity + delta).coerceAtLeast(0))
        if (next.quantity == 0) {
            removeItem(id)
        } else {
            repo.updatePantryItem(next)
            _state.update { it.copy(items = it.items.map { i -> if (i.id == id) next else i }) }
            schedulePush()
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

    // ---- On-device recipe generator ("pocket AI") ----

    /**
     * Compose a brand-new recipe from the current pantry. Runs fully on-device
     * and offline; no API key or network needed. The same pantry always yields
     * the same recipe, so it's stable and testable.
     */
    fun generateNewRecipe() {
        _state.update { it.copy(isGeneratingRecipe = true, generatedRecipe = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                RecipeMakerEngine.generate(_state.value.items)
            }
            _state.update { it.copy(generatedRecipe = result, isGeneratingRecipe = false) }
        }
    }

    fun clearGeneratedRecipe() = _state.update { it.copy(generatedRecipe = null) }

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
