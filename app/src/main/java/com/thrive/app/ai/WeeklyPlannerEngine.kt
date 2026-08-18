package com.thrive.app.ai

import com.thrive.app.data.model.PantryItem
import com.thrive.app.data.model.Recipe
import java.util.Locale

/** One planned evening. */
data class PlannedNight(
    val day: String,
    val suggestion: MealSuggestion,
)

/** Result of a budget-repair attempt: the (possibly changed) plan + honest note. */
data class OptimizeResult(
    val plan: WeeklyPlan,
    val changed: Boolean,
    val note: String,
)

/**
 * A full week of dinners plus the combined shopping list for what's missing.
 * Costs are labeled estimates — never fabricated store prices.
 */
data class WeeklyPlan(
    val nights: List<PlannedNight>,
    val budget: Double,
    val people: Int,
    val recipeCost: Double,
    val extraCost: Double,
    val combinedShopping: List<MissingItem>,
    val aiTip: String? = null,
    // Normalized, aisle-grouped shopping list (canonical names, merged
    // quantities, pantry-subtracted) — see IngredientNormalizer.
    val shoppingGroups: List<PlanShoppingGroup> = emptyList(),
    // Constraint context that produced this plan (kept so swaps/optimize
    // re-apply the same rules).
    val focus: String = "balanced",
    val preferredStore: String? = null,
    val restrictions: List<String> = emptyList(),
    val maxCookMinutes: Int = 0,
    val appliances: Set<String> = emptySet(),
    val requestSummary: String? = null,
    // Honest outcome of the last budget-repair attempt, when one ran.
    val repairNote: String? = null,
) {
    /** Honest cost of the week: what you'll buy plus the pantry value used. */
    val totalCost: Double get() = extraCost + pantryValueUsed

    /** Estimated dollar value of pantry items the plan consumes. */
    val pantryValueUsed: Double
        get() = nights.sumOf { it.suggestion.pantryValueUsed }

    val underBudget: Boolean get() = totalCost <= budget
    val remaining: Double get() = (budget - totalCost).coerceAtLeast(0.0)
    val overshoot: Double get() = (totalCost - budget).coerceAtLeast(0.0)
    val nightsCount: Int get() = nights.size

    val avgMinutes: Int
        get() = if (nights.isEmpty()) 0 else nights.sumOf { it.suggestion.recipe.totalMinutes } / nights.size

    val costPerMeal: Double
        get() = if (nights.isEmpty()) 0.0 else Math.round(totalCost / nights.size * 100) / 100.0

    val costPerServing: Double
        get() {
            val servings = nights.sumOf { it.suggestion.recipe.servings.coerceAtLeast(1) }
            return if (servings <= 0) 0.0 else Math.round(totalCost / servings * 100) / 100.0
        }

    /** How many shopping lines are already covered by the pantry. */
    val pantryCoveredCount: Int
        get() = shoppingGroups.sumOf { g -> g.items.count { it.haveInPantry } }
}

/**
 * Generates a week of dinners. Each night picks the best pantry-aware meal
 * that fits the per-night budget share (with a little slack), never repeats a
 * recipe, honors restrictions (allergies/avoided foods) and a max cook time,
 * and aggregates everything the user still needs to buy into one normalized,
 * aisle-grouped list. Works even with an empty pantry — it then picks the
 * cheapest meals. Fully deterministic and offline.
 */
object WeeklyPlannerEngine {

    val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /** Terms that identify a recipe as violating a restriction. */
    private val RESTRICTION_TERMS = mapOf(
        "peanut" to listOf("peanut", "peanuts"),
        "nuts" to listOf("peanut", "walnut", "almond", "cashew", "pecan", "hazelnut", "macadamia", "pistachio"),
        "shellfish" to listOf("shrimp", "crab", "lobster", "scallop", "clam", "mussel", "oyster", "prawn"),
        "dairy" to listOf("milk", "cheese", "butter", "cream", "yogurt", "parmesan", "mozzarella", "cheddar", "feta", "ricotta", "half and half", "sour cream"),
        "gluten" to listOf("flour", "pasta", "bread", "noodle", "spaghetti", "penne", "tortilla", "bun", "roll", "bagel", "soy sauce", "worcestershire"),
        "eggs" to listOf("egg", "eggs", "mayonnaise", "mayo"),
        "soy" to listOf("soy", "tofu", "tempeh", "edamame"),
        "pork" to listOf("pork", "bacon", "ham", "sausage"),
        "vegetarian" to listOf("chicken", "beef", "pork", "bacon", "ham", "turkey", "lamb", "steak", "fish", "salmon", "tuna", "shrimp", "sausage", "meatball"),
        "vegan" to listOf("chicken", "beef", "pork", "bacon", "ham", "turkey", "lamb", "steak", "fish", "salmon", "tuna", "shrimp", "sausage", "meatball", "egg", "milk", "cheese", "butter", "cream", "yogurt", "honey"),
    )

    private val LOCALE = Locale.US

    /** Does the recipe violate any restriction? */
    fun violatesRestrictions(recipe: Recipe, restrictions: List<String>): Boolean {
        if (restrictions.isEmpty()) return false
        val haystack = (recipe.name + " " + recipe.ingredients.joinToString(" ") { it.name }).lowercase(LOCALE)
        return restrictions.any { r ->
            RESTRICTION_TERMS[r]?.any { haystack.contains(it) } ?: false
        }
    }

    fun fitsCookTime(recipe: Recipe, maxCookMinutes: Int): Boolean =
        maxCookMinutes <= 0 || recipe.totalMinutes <= maxCookMinutes

    /**
     * True when the household's appliances can make this recipe. Untagged
     * recipes assume a basic stovetop/oven (always available), so an extra
     * appliance can only make MORE recipes eligible, never fewer.
     */
    fun fitsAppliances(recipe: Recipe, appliances: Set<String>): Boolean {
        if (recipe.requiredAppliances.isEmpty()) return true
        return recipe.requiredAppliances.all { it in appliances }
    }

    /** Eligible recipes for this request, in catalog order. */
    fun eligible(
        recipes: List<Recipe>,
        restrictions: List<String>,
        maxCookMinutes: Int,
        appliances: Set<String> = emptySet(),
    ): List<Recipe> =
        recipes.filter {
            fitsCookTime(it, maxCookMinutes) &&
                !violatesRestrictions(it, restrictions) &&
                fitsAppliances(it, appliances)
        }

    /**
     * Scores a recipe against the pantry, or builds the honest "everything
     * missing" suggestion when the pantry covers nothing (the meal still has
     * to be possible, and the shopping list still has to be real).
     */
    private fun scoreOrSynthetic(recipe: Recipe, pantry: List<PantryItem>, focus: String): MealSuggestion {
        val s = PantryMealEngine.scoreRecipe(recipe, pantry, focus)
        if (s != null) return s
        val missing = recipe.ingredients
            .filter { !PantryMealEngine.isStaple(it.name) && !it.optional }
            .map { MissingItem(it.name, PantryMealEngine.estimateIngredientPrice(it.name)) }
        return MealSuggestion(
            recipe = recipe,
            usedItems = emptyList(),
            expiringItemsUsed = emptyList(),
            missingItems = missing,
            coverageScore = -1.0,
            estimatedExtraCost = missing.sumOf { it.estCost },
        )
    }

    /** What a meal actually costs the household: new groceries + pantry value used. */
    private fun householdCost(s: MealSuggestion): Double = s.estimatedExtraCost + s.pantryValueUsed

    /**
     * Picks one night's meal from the remaining recipes, reusing the same
     * per-night budget logic as [plan]. Returns null when nothing fits.
     */
    private fun pickNight(
        candidates: List<Recipe>,
        pantry: List<PantryItem>,
        focus: String,
        perNightBudget: Double,
        slack: Double,
    ): MealSuggestion? {
        val scored = candidates.map { recipe ->
            val suggestion = scoreOrSynthetic(recipe, pantry, focus)
            Triple(suggestion, suggestion.coverageScore, suggestion.recipe.costDollars)
        }
        if (scored.isEmpty()) return null
        val fits = scored.filter { it.third <= perNightBudget * slack }
        return when {
            fits.isNotEmpty() -> fits.maxBy { it.second }.first
            else -> scored.minBy { it.third }.first
        }
    }

    /** Rebuilds a plan with one night replaced, recalculating derived state. */
    private fun withReplacement(
        plan: WeeklyPlan,
        index: Int,
        suggestion: MealSuggestion,
        pantry: List<PantryItem>,
    ): WeeklyPlan {
        val newNights = plan.nights.toMutableList()
        newNights[index] = PlannedNight(plan.nights[index].day, suggestion)
        val shopping = aggregateShopping(newNights)
        val recipeCost = newNights.sumOf { it.suggestion.recipe.costDollars }
        val extraCost = shopping.sumOf { it.estCost }
        val groups = buildGroups(newNights, pantry)
        return plan.copy(
            nights = newNights,
            recipeCost = recipeCost,
            extraCost = extraCost,
            combinedShopping = shopping,
            shoppingGroups = groups,
            repairNote = null,
        )
    }

    /** Aggregates the combined shopping list from a set of nights. */
    private fun aggregateShopping(planned: List<PlannedNight>): List<MissingItem> {
        val shopping = LinkedHashMap<String, MissingItem>()
        for (night in planned) {
            for (missing in night.suggestion.missingItems) {
                val existing = shopping[missing.name]
                shopping[missing.name] = if (existing == null) {
                    missing.copy(estCost = missing.estCost)
                } else {
                    MissingItem(missing.name, Math.round((existing.estCost + missing.estCost) * 100) / 100.0)
                }
            }
        }
        return shopping.values.toList()
    }

    private fun buildGroups(nights: List<PlannedNight>, pantry: List<PantryItem>): List<PlanShoppingGroup> =
        IngredientNormalizer.build(
            recipes = nights.map { it.suggestion.recipe },
            pantryNames = pantry.map { it.name },
            servingsPerRecipe = nights.firstOrNull()?.suggestion?.recipe?.servings ?: 4,
        )

    fun plan(
        pantry: List<PantryItem>,
        recipes: List<Recipe>,
        nights: Int = 7,
        budget: Double,
        people: Int = 4,
        focus: String = "balanced",
        restrictions: List<String> = emptyList(),
        maxCookMinutes: Int = 0,
        appliances: Set<String> = emptySet(),
        preferredStore: String? = null,
        requestSummary: String? = null,
    ): WeeklyPlan {
        val nightCount = nights.coerceIn(1, 7)
        val perNightBudget = if (nightCount > 0) budget / nightCount else budget
        val slack = 1.2
        val pool = eligible(recipes, restrictions, maxCookMinutes, appliances)
        val used = mutableSetOf<String>()
        val planned = mutableListOf<PlannedNight>()

        for (i in 0 until nightCount) {
            val day = DAYS[i % DAYS.size]
            val candidates = pool.filter { it.id !in used }
            val pick = pickNight(candidates, pantry, focus, perNightBudget, slack) ?: break
            planned += PlannedNight(day, pick)
            used += pick.recipe.id
        }

        val shopping = aggregateShopping(planned)
        val recipeCost = planned.sumOf { it.suggestion.recipe.costDollars }
        val extraCost = shopping.sumOf { it.estCost }
        val groups = buildGroups(planned, pantry)

        return WeeklyPlan(
            nights = planned,
            budget = budget,
            people = people,
            recipeCost = recipeCost,
            extraCost = extraCost,
            combinedShopping = shopping,
            shoppingGroups = groups,
            focus = focus,
            preferredStore = preferredStore,
            restrictions = restrictions,
            maxCookMinutes = maxCookMinutes,
            appliances = appliances,
            requestSummary = requestSummary,
        )
    }

    /**
     * Swaps ONE night (acceptance: the other nights stay exactly the same —
     * only the swapped meal, the shopping list, and the totals change). The
     * replacement keeps every constraint from the original request.
     * Returns null when no eligible replacement exists.
     */
    fun swapNight(
        plan: WeeklyPlan,
        index: Int,
        pantry: List<PantryItem>,
        recipes: List<Recipe>,
    ): WeeklyPlan? {
        if (index !in plan.nights.indices) return null
        // Keep the other nights AND the meal being swapped out (a "swap" that
        // returns the same dinner is a silent no-op, not a swap).
        val currentId = plan.nights[index].suggestion.recipe.id
        val keepIds = plan.nights.mapIndexedNotNull { i, n -> n.suggestion.recipe.id.takeIf { i != index } }.toSet() + currentId
        val pool = eligible(recipes, plan.restrictions, plan.maxCookMinutes, plan.appliances)
            .filter { it.id !in keepIds }
        val perNightBudget = if (plan.nights.isNotEmpty()) plan.budget / plan.nights.size else plan.budget
        val pick = pickNight(pool, pantry, plan.focus, perNightBudget, 1.2) ?: return null
        return withReplacement(plan, index, pick, pantry)
    }

    /**
     * Deterministic budget repair: while the plan is over budget, swap the
     * most expensive night for the cheapest eligible unused meal and
     * recalculate. Repeats up to 4 times. If the plan still can't fit the
     * budget, the note explains the honest floor instead of faking success.
     */
    fun optimize(
        plan: WeeklyPlan,
        pantry: List<PantryItem>,
        recipes: List<Recipe>,
    ): OptimizeResult {
        var current = plan
        var changed = false
        var rounds = 0
        // Repair is measured in what the household actually spends (new
        // groceries + pantry value used), not the catalog's recipe.costDollars
        // — the two estimates can disagree, and judging improvement by a
        // different metric than the one we optimize is how repairs stall.
        while (!current.underBudget && rounds < 4) {
            val mostExpensive = current.nights
                .mapIndexed { i, n -> i to householdCost(n.suggestion) }
                .maxByOrNull { it.second }?.first ?: break
            val currentId = current.nights[mostExpensive].suggestion.recipe.id
            val keepIds = current.nights
                .mapIndexedNotNull { i, n -> n.suggestion.recipe.id.takeIf { i != mostExpensive } }
                .toSet() + currentId
            val pool = eligible(recipes, current.restrictions, current.maxCookMinutes, current.appliances)
                .filter { it.id !in keepIds }
            if (pool.isEmpty()) break
            val cheapest = pool.minByOrNull { r -> householdCost(scoreOrSynthetic(r, pantry, current.focus)) } ?: break
            val currentCost = householdCost(current.nights[mostExpensive].suggestion)
            if (householdCost(scoreOrSynthetic(cheapest, pantry, current.focus)) >= currentCost - 0.01) break
            current = withReplacement(current, mostExpensive, scoreOrSynthetic(cheapest, pantry, current.focus), pantry)
            changed = true
            rounds++
        }
        val note = if (current.underBudget) {
            if (changed) "Optimized — swapped the priciest meals to fit your budget (${MoneyLabel.fmt(current.totalCost)} of ${MoneyLabel.fmt(current.budget)})."
            else "Already within budget — ${MoneyLabel.fmt(current.remaining)} to spare."
        } else {
            "The cheapest possible week from these recipes is ${MoneyLabel.fmt(current.totalCost)} — ${MoneyLabel.fmt(current.overshoot)} over your ${MoneyLabel.fmt(current.budget)} budget. Try a bigger budget, fewer nights, or adding pantry items."
        }
        return OptimizeResult(current, changed, note)
    }

    /** Small formatter so the engine stays free of UI concerns. */
    private object MoneyLabel {
        fun fmt(v: Double): String = if (v == v.toInt().toDouble()) "\$${v.toInt()}" else "\$${Math.round(v * 100) / 100.0}"
    }
}
