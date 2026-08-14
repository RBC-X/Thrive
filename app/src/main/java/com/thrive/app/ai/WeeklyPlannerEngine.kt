package com.thrive.app.ai

import com.thrive.app.data.model.PantryItem
import com.thrive.app.data.model.Recipe

/** One planned evening. */
data class PlannedNight(
    val day: String,
    val suggestion: MealSuggestion,
)

/** A full week of dinners plus the combined shopping list for what's missing. */
data class WeeklyPlan(
    val nights: List<PlannedNight>,
    val budget: Double,
    val people: Int,
    val recipeCost: Double,
    val extraCost: Double,
    val combinedShopping: List<MissingItem>,
    val aiTip: String? = null,
) {
    /**
     * Honest cost of the week: what you'll buy (extraCost) plus the estimated
     * value of pantry items you'll consume. recipe.costDollars already covers
     * EVERY ingredient, so adding the full recipe cost again would charge
     * pantry-covered ingredients twice. We therefore count the pantry share
     * once, as its value used, and the shopping list once, as new spend.
     */
    val totalCost: Double get() = extraCost + pantryValueUsed

    /** Estimated dollar value of pantry items the plan consumes. */
    val pantryValueUsed: Double
        get() = nights.sumOf { it.suggestion.pantryValueUsed }

    val underBudget: Boolean get() = totalCost <= budget
    val remaining: Double get() = (budget - totalCost).coerceAtLeast(0.0)
    val overshoot: Double get() = (totalCost - budget).coerceAtLeast(0.0)
    val nightsCount: Int get() = nights.size
}

/**
 * Generates a week of dinners. Each night picks the best pantry-aware meal
 * that fits the per-night budget share (with a little slack), never repeats a
 * recipe, and aggregates everything the user still needs to buy into one list.
 * Works even with an empty pantry — it then simply picks the cheapest meals.
 */
object WeeklyPlannerEngine {

    val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    fun plan(
        pantry: List<PantryItem>,
        recipes: List<Recipe>,
        nights: Int = 7,
        budget: Double,
        people: Int = 4,
        focus: String = "balanced",
    ): WeeklyPlan {
        val nightCount = nights.coerceIn(1, 7)
        val perNightBudget = if (nightCount > 0) budget / nightCount else budget
        val slack = 1.2  // allow a little overage; cheaper nights compensate
        val used = mutableSetOf<String>()
        val planned = mutableListOf<PlannedNight>()

        for (i in 0 until nightCount) {
            val day = DAYS[i % DAYS.size]
            val candidates = recipes
                .filter { it.id !in used }
                .mapNotNull { recipe ->
                    val suggestion = PantryMealEngine.scoreRecipe(recipe, pantry, focus)
                    if (suggestion != null) {
                        Triple(suggestion, suggestion.coverageScore, suggestion.recipe.costDollars)
                    } else {
                        // No pantry overlap: still eligible on cost alone.
                        val missing = recipe.ingredients
                            .filter { !PantryMealEngine.isStaple(it.name) && !it.optional }
                            .map { MissingItem(it.name, PantryMealEngine.estimateIngredientPrice(it.name)) }
                        val synthetic = MealSuggestion(
                            recipe = recipe,
                            usedItems = emptyList(),
                            expiringItemsUsed = emptyList(),
                            missingItems = missing,
                            coverageScore = -1.0,
                            estimatedExtraCost = missing.sumOf { it.estCost },
                        )
                        Triple(synthetic, -1.0, recipe.costDollars)
                    }
                }

            // Prefer meals that fit the per-night budget; fall back to cheapest overall.
            val fits = candidates.filter { it.third <= perNightBudget * slack }
            val pick = when {
                fits.isNotEmpty() -> fits.maxBy { it.second }
                candidates.isNotEmpty() -> candidates.minBy { it.third }
                else -> null
            } ?: break

            planned += PlannedNight(day, pick.first)
            used += pick.first.recipe.id
        }

        // Aggregate missing ingredients across all nights.
        val shopping = LinkedHashMap<String, MissingItem>()
        for (night in planned) {
            for (missing in night.suggestion.missingItems) {
                val existing = shopping[missing.name]
                shopping[missing.name] = if (existing == null) {
                    missing.copy(estCost = missing.estCost)
                } else {
                    MissingItem(missing.name, existing.estCost + missing.estCost)
                }
            }
        }

        val recipeCost = planned.sumOf { it.suggestion.recipe.costDollars }
        val extraCost = shopping.values.sumOf { it.estCost }

        return WeeklyPlan(
            nights = planned,
            budget = budget,
            people = people,
            recipeCost = recipeCost,
            extraCost = extraCost,
            combinedShopping = shopping.values.toList(),
        )
    }
}
