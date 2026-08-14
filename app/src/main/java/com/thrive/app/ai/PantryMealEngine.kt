package com.thrive.app.ai

import com.thrive.app.data.model.Ingredient
import com.thrive.app.data.model.PantryItem
import com.thrive.app.data.model.Recipe
import java.util.Locale

/** An ingredient the user will need to buy for this meal. */
data class MissingItem(val name: String, val estCost: Double)

/** A meal suggestion produced from the user's pantry. */
data class MealSuggestion(
    val recipe: Recipe,
    val usedItems: List<String>,
    val expiringItemsUsed: List<String>,
    val missingItems: List<MissingItem>,
    val coverageScore: Double,
    val estimatedExtraCost: Double,
    val aiTip: String? = null,
) {
    val usesCount: Int get() = usedItems.size
    val isZeroShopping: Boolean get() = missingItems.isEmpty()

    /**
     * Estimated value of the pantry items this meal consumes: the recipe's
     * full cost scaled by the share of its non-staple ingredients the pantry
     * covers. Lets a week's total count pantry goods once without also
     * charging their full recipe cost again.
     */
    val pantryValueUsed: Double get() = (recipe.costDollars * usedShare).coerceAtLeast(0.0)

    /** Fraction of the recipe's required (non-staple) ingredients already owned. */
    val usedShare: Double
        get() {
            val required = recipe.ingredients.count {
                !PantryMealEngine.isStaple(it.name) && !it.optional
            }
            return if (required <= 0) 0.0 else (usedItems.size.toDouble() / required).coerceIn(0.0, 1.0)
        }
}

/**
 * Local meal-planning engine. Scores every known recipe against the user's
 * pantry: it rewards recipes that use what the user already has (with a bonus
 * for items close to expiry) and penalizes recipes that would require buying
 * many extra ingredients. Fully deterministic and offline.
 */
object PantryMealEngine {

    /** Pantry staples assumed to always be available. */
    private val STAPLES = setOf(
        "salt", "pepper", "black pepper", "cooking oil", "olive oil", "water", "sugar",
        "flour", "garlic", "onion", "butter",
    )

    /** Approximate prices for common missing ingredients (dollars). */
    private val PRICE_MAP = mapOf(
        "chicken breast" to 4.5, "chicken thighs" to 4.0, "ground beef" to 5.0,
        "ground turkey" to 4.5, "pork chops" to 4.0, "bacon" to 4.0,
        "eggs" to 2.5, "milk" to 3.0, "cheddar cheese" to 3.5, "mozzarella" to 3.0,
        "parmesan" to 3.5, "cream" to 2.5, "sour cream" to 2.0, "yogurt" to 2.5,
        "rice" to 2.5, "pasta" to 1.5, "spaghetti" to 1.5, "penne" to 1.5,
        "bread" to 2.5, "tortillas" to 2.5, "flour tortillas" to 2.5,
        "potatoes" to 3.0, "sweet potatoes" to 2.5, "carrots" to 1.5, "celery" to 1.5,
        "broccoli" to 2.0, "spinach" to 2.5, "lettuce" to 2.0, "tomatoes" to 2.5,
        "bell pepper" to 1.5, "zucchini" to 1.5, "mushrooms" to 2.0,
        "canned tomatoes" to 1.5, "tomato sauce" to 1.5, "tomato paste" to 1.0,
        "black beans" to 1.5, "kidney beans" to 1.5, "chickpeas" to 1.5,
        "corn" to 1.5, "peas" to 1.5, "green beans" to 2.0,
        "salsa" to 2.5, "soy sauce" to 2.0, "hot sauce" to 2.5, "mustard" to 2.0,
        "ketchup" to 2.0, "mayonnaise" to 3.0, "pasta sauce" to 2.5,
        "lemon" to 0.8, "lime" to 0.8, "bananas" to 1.5, "apples" to 3.0,
        "frozen vegetables" to 2.0, "frozen peas" to 2.0, "frozen corn" to 2.0,
        "canned tuna" to 1.5, "canned salmon" to 3.0, "sausage" to 3.5,
        "diced tomatoes" to 1.5, "broth" to 2.0, "chicken broth" to 2.0,
        "vegetable broth" to 2.0, "greek yogurt" to 3.0, "taco seasoning" to 1.5,
        "pasta sauce jar" to 2.5, "marinara" to 2.5, "cheddar" to 3.5,
        "peanut butter" to 3.0, "jelly" to 2.5, "honey" to 3.5, "maple syrup" to 4.0,
        "rolled oats" to 2.5, "quick oats" to 2.5, "cocoa powder" to 3.0,
        "chocolate chips" to 3.0, "vanilla extract" to 3.0, "baking powder" to 1.5,
        "baking soda" to 1.5, "panko" to 2.5, "breadcrumbs" to 2.0,
        "ranch dressing" to 3.0, "bbq sauce" to 2.5, "teriyaki sauce" to 3.0,
        "worcestershire" to 3.0, "apple cider vinegar" to 2.5, "red wine vinegar" to 2.5,
        "coconut milk" to 2.5, "curry paste" to 3.0, "ginger" to 1.5,
        "cilantro" to 1.0, "parsley" to 1.0, "basil" to 1.5, "thyme" to 1.5,
        "paprika" to 2.0, "cumin" to 2.0, "chili powder" to 2.0, "italian seasoning" to 2.0,
        "bay leaves" to 2.0, "dijon mustard" to 2.5, "green onions" to 1.0,
        "scallions" to 1.0, "shallot" to 1.0, "avocado" to 1.5, "cucumber" to 1.5,
        "red onion" to 1.0, "white rice" to 2.5, "brown rice" to 3.0,
        "quinoa" to 4.0, "lentils" to 2.0, "couscous" to 2.5, "tuna" to 1.5,
    )

    private val STOPWORDS = setOf(
        "fresh", "boneless", "skinless", "uncooked", "cooked", "plain", "whole",
        "organic", "large", "small", "medium", "raw", "low-fat", "low fat", "2%",
        "store-bought", "store bought", "leftover", "canned", "diced", "shredded",
        "grated", "chopped", "minced", "sliced", "ground", "extra", "virgin",
        "big", "smaller", "flavored",
    )

    private fun tokens(s: String): Set<String> {
        val normalized = s.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9 ]"), " ")
        return normalized.split(" ").filter { it.isNotBlank() && it.length > 1 }
            .filterNot { it in STOPWORDS }
            .toSet()
    }

    /** Does the pantry item plausibly cover this ingredient? */
    private fun matches(pantryName: String, ingredientName: String): Boolean {
        val p = tokens(pantryName)
        val i = tokens(ingredientName)
        if (p.isEmpty() || i.isEmpty()) return false
        if (p.any { it in i } || i.any { it in p }) return true
        // allow subset coverage for multi-token items like "boneless chicken breast"
        return p.count { it in i } >= minOf(2, i.size) && p.isNotEmpty()
    }

    private fun estPrice(ingredientName: String): Double {
        val key = ingredientName.lowercase(Locale.US).trim()
        PRICE_MAP[key]?.let { return it }
        // partial key lookup
        PRICE_MAP.entries.firstOrNull { key.contains(it.key) }?.let { return it.value }
        return 2.00
    }

    fun isStaple(ingredientName: String): Boolean {
        val norm = ingredientName.lowercase(Locale.US).trim()
        if (norm in STAPLES) return true
        return tokens(norm).any { it in STAPLES }
    }

    /** Public estimate used when building shopping lists from a recipe. */
    fun estimateIngredientPrice(ingredientName: String): Double = estPrice(ingredientName)

    /**
     * Scores a single recipe against the pantry. Returns null when the recipe
     * uses none of the user's items (or has no measurable ingredients).
     */
    fun scoreRecipe(
        recipe: Recipe,
        pantry: List<PantryItem>,
        focus: String = "balanced",
    ): MealSuggestion? {
        val now = System.currentTimeMillis()
        val expiringSoon = pantry.filter { it.expiresAt != null && it.expiresAt - now < 3 * 24 * 3600_000L }
        val pantryNames = pantry.map { it.name }
        val expiringNames = expiringSoon.map { it.name }

        var used = 0
        var required = 0
        var expiringUsed = 0
        val usedNames = LinkedHashSet<String>()
        val missing = mutableListOf<MissingItem>()

        for (ing in recipe.ingredients) {
            if (isStaple(ing.name)) continue
            required++
            val hit = pantryNames.firstOrNull { matches(it, ing.name) }
            if (hit != null) {
                used++
                usedNames.add(hit)
                if (hit in expiringNames) expiringUsed++
            } else if (!ing.optional) {
                missing.add(MissingItem(ing.name, estPrice(ing.name)))
            }
        }
        if (required == 0) return null

        val coverage = used.toDouble() / required
        val expiringBonus = if (focus == "use_expiring") expiringUsed * 1.6 else expiringUsed * 0.8
        val missingPenalty = missing.size * 0.45
        val costPenalty = minOf(missing.sumOf { it.estCost }, 14.0) * 0.05
        val score = coverage * 3.0 + expiringBonus - missingPenalty - costPenalty
        if (used == 0) return null

        return MealSuggestion(
            recipe = recipe,
            usedItems = usedNames.toList(),
            expiringItemsUsed = expiringNames.filter { it in usedNames },
            missingItems = missing,
            coverageScore = score,
            estimatedExtraCost = missing.sumOf { it.estCost },
        )
    }

    /**
     * Returns the top meal suggestions for the given pantry, best first.
     * @param focus when "use_expiring" expiring items weigh more heavily.
     */
    fun suggest(
        pantry: List<PantryItem>,
        recipes: List<Recipe>,
        focus: String = "balanced",
        limit: Int = 3,
    ): List<MealSuggestion> =
        recipes.mapNotNull { scoreRecipe(it, pantry, focus) }
            .sortedByDescending { it.coverageScore }
            .take(limit)

    /** Builds a step-by-step plan text for a suggestion (used by AI + display). */
    fun stepsFor(suggestion: MealSuggestion): List<String> = suggestion.recipe.steps
}
