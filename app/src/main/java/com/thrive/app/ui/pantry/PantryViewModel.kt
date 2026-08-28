package com.thrive.app.ui.pantry

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thrive.app.ai.AiAdvisor
import com.thrive.app.ai.AiRecipeMaker
import com.thrive.app.ai.AiService
import com.thrive.app.ai.DealFinderEngine
import com.thrive.app.ai.TripPlan
import com.thrive.app.ai.GeneratedRecipe
import com.thrive.app.ai.MealSuggestion
import com.thrive.app.ai.PantryMealEngine
import com.thrive.app.ai.RecipeMakerEngine
import com.thrive.app.ai.WeeklyPlan
import com.thrive.app.ai.WeeklyPlannerEngine
import com.thrive.app.data.ThriveRepository
import com.thrive.app.data.model.PantryItem
import com.thrive.app.data.remote.WebRecipeSearch
import com.thrive.app.data.remote.WebSearchState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class PantryUiState(
    val items: List<PantryItem> = emptyList(),
    val suggestions: List<MealSuggestion>? = null,
    val isLoadingMeals: Boolean = false,
    val weeklyPlan: WeeklyPlan? = null,
    val isPlanningWeek: Boolean = false,
    val generatedRecipe: GeneratedRecipe? = null,
    val isGeneratingRecipe: Boolean = false,
    val webSearch: WebSearchState = WebSearchState.Idle,
    val aiEnabled: Boolean = false,
    // Live-deal matching of the weekly plan's shopping list: per-store trip
    // totals with real prices where a verified deal exists (null until a plan
    // has been generated and matched).
    val tripPlan: TripPlan? = null,
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
    private val onDevice = (app as com.thrive.app.ThriveApp).onDeviceLlm
    private val aiMaker = AiRecipeMaker(ai, onDevice)

    val catalog: List<com.thrive.app.data.model.CatalogItem> by lazy { repo.catalog }

    private val _state = MutableStateFlow(PantryUiState(items = repo.loadPantry()))
    val state: StateFlow<PantryUiState> = _state.asStateFlow()

    init {
        _state.update { it.copy(aiEnabled = ai.isEnabled) }
    }

    /** Debounced pantry-only push so a burst of edits is one upload. */
    private fun schedulePush() {
        repo.scheduleAccountSync()
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

    private var mealsJob: Job? = null

    fun generateMeals(focus: String = "balanced") {
        mealsJob?.cancel()
        _state.update { it.copy(isLoadingMeals = true, suggestions = null) }
        mealsJob = viewModelScope.launch {
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
                            budgetHint = com.thrive.app.util.Money.fmtCompact(s.recipe.costDollars) + " total",
                        )
                    }.getOrNull()
                } else null
                s.copy(aiTip = tip)
            }
            _state.update { it.copy(suggestions = enriched, isLoadingMeals = false, aiEnabled = ai.isEnabled) }
        }
    }

    fun clearSuggestions() = _state.update { it.copy(suggestions = null) }

    // ---- Web discovery for meal ideas ----

    private var webSearchJob: Job? = null

    /**
     * Looks up "search the web for this" alternatives for a meal idea via the
     * sync server's Exa-backed endpoint. Results are discovery leads, never
     * verified claims, and every failure (no server, offline, HTTP error,
     * malformed payload) degrades to an honest state — the local suggestion
     * list stays untouched.
     */
    fun searchWebFor(mealName: String) {
        webSearchJob?.cancel()
        _state.update { it.copy(webSearch = WebSearchState.Loading(mealName)) }
        webSearchJob = viewModelScope.launch {
            val state = WebRecipeSearch.fetch(repo.syncBaseUrl, mealName)
            _state.update { it.copy(webSearch = state) }
        }
    }

    fun clearWebSearch() = _state.update { it.copy(webSearch = WebSearchState.Idle) }

    // ---- On-device recipe generator ("pocket AI") ----

    private var recipeVariant = 0
    private var recipeJob: Job? = null

    /**
     * Compose a brand-new recipe from the current pantry. When an AI provider
     * is configured, the pantry is sent to a real LLM which writes a fresh
     * dish for THIS pantry; on any failure (offline, no key, bad response) it
     * falls back to the deterministic on-device engine, so the feature always
     * works with or without an API key. A previous in-flight generation is
     * cancelled so rapid "Try another" taps always end on the newest recipe.
     */
    fun generateNewRecipe() {
        recipeJob?.cancel()
        _state.update { it.copy(isGeneratingRecipe = true, generatedRecipe = null) }
        recipeJob = viewModelScope.launch {
            val items = _state.value.items
            val result = withContext(Dispatchers.Default) {
                // Real generative AI first; deterministic engine as fallback.
                aiMaker.generate(items, variant = recipeVariant)
                    ?: RecipeMakerEngine.generate(items, variant = recipeVariant)
            }
            _state.update { it.copy(generatedRecipe = result, isGeneratingRecipe = false) }
        }
    }

    /** Roll a different recipe from the same pantry (different method/sauce). */
    fun tryAnotherRecipe() {
        recipeVariant += 1
        generateNewRecipe()
    }

    /**
     * Accept the generated recipe. Returns the ingredients to buy — only the
     * missing ones that aren't already in the pantry — and hides the card so
     * the caller can add them to the shopping list.
     */
    fun acceptRecipe(): List<Triple<String, String, String>> {
        val gen = _state.value.generatedRecipe ?: return emptyList()
        val pantryNames = _state.value.items.map { it.name.lowercase(Locale.US).trim() }
        val toBuy = gen.missingToBuy.filter { (name, _, _) ->
            val n = name.lowercase(Locale.US).trim()
            pantryNames.none { p -> p.contains(n) || n.contains(p) }
        }
        clearGeneratedRecipe()
        return toBuy
    }

    fun clearGeneratedRecipe() = _state.update { it.copy(generatedRecipe = null) }

    // ---- Weekly planner ----

    /** Everything needed to rebuild (or re-run) the same week. */
    data class PlanParams(
        val people: Int,
        val budget: Double,
        val nights: Int = 7,
        val focus: String = "balanced",
        val restrictions: List<String> = emptyList(),
        val maxCookMinutes: Int = 0,
        val appliances: Set<String> = emptySet(),
        val preferredStore: String? = null,
        val requestSummary: String? = null,
    )

    private val planner = WeeklyPlannerEngine
    private var lastPlanParams: PlanParams? = null
    private var weekPlanJob: Job? = null

    fun generateWeeklyPlan(
        people: Int,
        budget: Double,
        nights: Int = 7,
        focus: String = "balanced",
        restrictions: List<String> = emptyList(),
        maxCookMinutes: Int = 0,
        appliances: Set<String> = emptySet(),
        preferredStore: String? = null,
        requestSummary: String? = null,
    ) {
        weekPlanJob?.cancel()
        lastPlanParams = PlanParams(
            people = people,
            budget = budget,
            nights = nights,
            focus = focus,
            restrictions = restrictions,
            maxCookMinutes = maxCookMinutes,
            appliances = appliances,
            preferredStore = preferredStore,
            requestSummary = requestSummary,
        )
        _state.update { it.copy(isPlanningWeek = true, weeklyPlan = null) }
        weekPlanJob = viewModelScope.launch {
            val items = _state.value.items
            // Merge Settings-persisted appliances with NL-parsed ones — extra
            // appliances never exclude recipes, only add eligible ones.
            val savedAppliances = (getApplication<com.thrive.app.ThriveApp>()).settings.getAppliances()
            val mergedAppliances = savedAppliances + appliances
            val plan = withContext(Dispatchers.Default) {
                planner.plan(
                    pantry = items,
                    recipes = repo.recipes,
                    nights = nights,
                    budget = budget,
                    people = people,
                    focus = focus,
                    restrictions = restrictions,
                    maxCookMinutes = maxCookMinutes,
                    appliances = mergedAppliances,
                    preferredStore = preferredStore,
                    requestSummary = requestSummary,
                )
            }
            val trip = withContext(Dispatchers.Default) {
                runCatching { DealFinderEngine.tripFor(plan, repo.deals) }.getOrNull()
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
            _state.update {
                it.copy(
                    weeklyPlan = enriched,
                    tripPlan = trip,
                    isPlanningWeek = false,
                    aiEnabled = ai.isEnabled,
                )
            }
        }
    }

    /**
     * Swap ONE night. Every other night, and all the request's constraints,
     * stay exactly as they were; only the swapped meal and the derived
     * shopping list/totals change. If no eligible replacement exists the
     * current plan stays and an honest note explains why.
     */
    fun swapNight(index: Int) {
        val plan = _state.value.weeklyPlan ?: return
        weekPlanJob?.cancel()
        _state.update { it.copy(isPlanningWeek = true) }
        weekPlanJob = viewModelScope.launch {
            val items = _state.value.items
            val swapped = withContext(Dispatchers.Default) {
                planner.swapNight(plan, index, items, repo.recipes)
            }
            val finalPlan = swapped ?: plan.copy(
                repairNote = "No other meal fits your restrictions for that night — try loosening a constraint."
            )
            val trip = withContext(Dispatchers.Default) {
                runCatching { DealFinderEngine.tripFor(finalPlan, repo.deals) }.getOrNull()
            }
            _state.update {
                it.copy(
                    weeklyPlan = finalPlan,
                    tripPlan = trip,
                    isPlanningWeek = false,
                )
            }
        }
    }

    /**
     * Deterministic budget repair: swap the priciest meals for cheaper
     * eligible ones until the plan fits the budget or honestly explains the
     * cheapest floor it can reach. Never fakes success.
     */
    fun optimizePlan() {
        val plan = _state.value.weeklyPlan ?: return
        weekPlanJob?.cancel()
        _state.update { it.copy(isPlanningWeek = true) }
        weekPlanJob = viewModelScope.launch {
            val items = _state.value.items
            val result = withContext(Dispatchers.Default) {
                planner.optimize(plan, items, repo.recipes)
            }
            val optimized = result.plan.copy(repairNote = result.note)
            val trip = withContext(Dispatchers.Default) {
                runCatching { DealFinderEngine.tripFor(optimized, repo.deals) }.getOrNull()
            }
            _state.update {
                it.copy(weeklyPlan = optimized, tripPlan = trip, isPlanningWeek = false)
            }
        }
    }

    fun rePlan() {
        lastPlanParams?.let { p ->
            generateWeeklyPlan(
                people = p.people,
                budget = p.budget,
                nights = p.nights,
                focus = p.focus,
                restrictions = p.restrictions,
                maxCookMinutes = p.maxCookMinutes,
                appliances = p.appliances,
                preferredStore = p.preferredStore,
                requestSummary = p.requestSummary,
            )
        }
    }
}
