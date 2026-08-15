package com.thrive.app.ai

import com.thrive.app.data.model.Ingredient
import com.thrive.app.data.model.PantryItem
import com.thrive.app.data.model.Recipe
import com.thrive.app.util.Money
import java.util.Locale

/**
 * A brand-new recipe composed by the on-device generator.
 */
data class GeneratedRecipe(
    val recipe: Recipe,
    val usedItems: List<String>,
    val missingItems: List<String>,
    val estimatedCost: Double,
)

/**
 * On-device recipe generator ("pocket AI").
 *
 * Composes genuinely NEW recipes — not picks from the bundled catalog — by
 * matching what the user has in their pantry to real cooking techniques:
 * a protein + starch + vegetable + sauce, prepared by a chosen method
 * (skillet, sheet pan, one pot, slow cooker). Deterministic, fully offline,
 * no API keys, and every generated recipe carries honest step-by-step
 * instructions and a cost estimate.
 */
object RecipeMakerEngine {

    private val LOCALE = Locale.US

    private val PROTEINS = mapOf(
        "chicken breast" to 4.5, "chicken thighs" to 4.0, "chicken" to 4.0,
        "ground beef" to 5.0, "beef" to 5.5, "ground turkey" to 4.5,
        "pork chops" to 4.0, "pork" to 4.0, "bacon" to 4.0,
        "sausage" to 3.5, "shrimp" to 6.0, "fish" to 5.0, "salmon" to 6.0,
        "tuna" to 1.5, "eggs" to 2.5, "tofu" to 2.5, "black beans" to 1.5,
        "kidney beans" to 1.5, "chickpeas" to 1.5, "lentils" to 2.0,
        "ham" to 3.5, "turkey" to 4.0, "steak" to 6.0,
    )

    private val STARCHES = mapOf(
        "rice" to 2.5, "pasta" to 1.5, "spaghetti" to 1.5, "penne" to 1.5,
        "macaroni" to 1.5, "noodles" to 2.0, "potatoes" to 3.0,
        "sweet potatoes" to 2.5, "tortillas" to 2.5, "bread" to 2.5,
        "rolls" to 2.5, "quinoa" to 3.5, "couscous" to 2.5, "grits" to 2.0,
        "polenta" to 2.5, "frozen fries" to 2.5,
    )

    private val VEGGIES = mapOf(
        "broccoli" to 2.0, "carrots" to 1.5, "celery" to 1.5, "spinach" to 2.5,
        "lettuce" to 2.0, "tomatoes" to 2.5, "bell pepper" to 1.5,
        "onion" to 1.0, "garlic" to 1.0, "zucchini" to 1.5, "mushrooms" to 2.0,
        "peas" to 1.5, "corn" to 1.5, "green beans" to 2.0, "cauliflower" to 2.5,
        "kale" to 2.5, "cabbage" to 2.0, "squash" to 2.0, "asparagus" to 3.0,
        "frozen vegetables" to 2.0, "frozen peas" to 2.0, "frozen corn" to 2.0,
        "frozen broccoli" to 2.5, "mixed vegetables" to 2.0,
    )

    private val SAUCES = mapOf(
        "salsa" to 2.5, "pasta sauce" to 2.5, "marinara" to 2.5, "soy sauce" to 2.0,
        "hot sauce" to 2.5, "teriyaki" to 3.0, "bbq sauce" to 2.5,
        "buffalo sauce" to 2.5, "coconut milk" to 2.5, "curry paste" to 3.0,
        "tomato sauce" to 1.5, "tomato paste" to 1.0, "diced tomatoes" to 1.5,
        "canned tomatoes" to 1.5, "ranch" to 2.5, "cream of mushroom" to 2.0,
        "cream of chicken" to 2.0, "broth" to 2.0, "chicken broth" to 2.0,
        "vegetable broth" to 2.0, "taco seasoning" to 1.5, "chili powder" to 2.0,
        "peanut sauce" to 3.0,
    )

    private val DAIRY = setOf(
        "cheddar", "cheese", "mozzarella", "parmesan", "cream", "sour cream",
        "yogurt", "greek yogurt", "milk", "butter", "cream cheese", "feta",
    )

    /** Pantry staples always assumed present. */
    private val STAPLES = setOf(
        "salt", "pepper", "black pepper", "cooking oil", "olive oil", "water",
        "sugar", "flour", "garlic", "onion", "butter",
    )

    private fun norm(s: String): String = s.lowercase(Locale.US).trim()

    /** Match a pantry item to a known ingredient class, longest-key-first. */
    private fun matchItem(name: String, table: Map<String, Double>): String? =
        table.keys
            .filter { norm(it) in norm(name) || norm(name) in norm(it) }
            .maxByOrNull { it.length }

    private fun findItem(items: List<PantryItem>, table: Map<String, Double>): Pair<String, Double>? {
        for (i in items) {
            val hit = matchItem(i.name, table) ?: continue
            return hit to table.getValue(hit)
        }
        return null
    }

    private fun title(s: String): String =
        s.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    /**
     * Generate a new dinner recipe from the pantry. Deterministic: the same
     * pantry always yields the same recipe (seeded by sorted item names), so
     * tests can assert structure and users get stable results per pantry.
     */
    fun generate(items: List<PantryItem>, focus: String = "balanced"): GeneratedRecipe {
        val pantry = items.distinctBy { norm(it.name) }
        val protein = findItem(pantry, PROTEINS)
        val starch = findItem(pantry, STARCHES)
        val veg = findItem(pantry, VEGGIES)
        val sauce = findItem(pantry, SAUCES)
        val dairyNames = pantry.map { it.name }.filter { n ->
            DAIRY.any { norm(it) in norm(n) }
        }

        val proteinName = protein?.first ?: "canned beans"
        val proteinCost = protein?.second ?: 2.0
        val starchName = starch?.first ?: "rice"
        val starchCost = starch?.second ?: 2.5
        val vegName = veg?.first ?: "frozen vegetables"
        val vegCost = veg?.second ?: 2.0
        val sauceName = sauce?.first
        val dairyName = dairyNames.firstOrNull()

        val used = listOfNotNull(protein?.first, starch?.first, veg?.first, sauce?.first, dairyName)

        // Cooking method chosen deterministically from the pantry fingerprint.
        val seedSum = pantry.sumOf { norm(it.name).hashCode().toLong() }
        val methodIdx = (((seedSum % 4) + 4) % 4).toInt()
        val method = when (methodIdx) {
            0 -> "skillet"
            1 -> "sheet pan"
            2 -> "one pot"
            else -> "slow cooker"
        }

        val dishName = buildString {
            if (dairyName != null && veg != null && protein != null) {
                append("Creamy ")
                append(title(proteinName))
                append(" and ")
                append(title(vegName))
                append(" Bake")
            } else if (sauceName != null && protein != null) {
                when {
                    norm(sauceName).contains("curry") -> append(title(sauceName).removeSuffix(" paste") + " " + title(proteinName))
                    norm(sauceName).contains("soy") || norm(sauceName).contains("teriyaki") -> append("Soy-Glazed " + title(proteinName))
                    norm(sauceName).contains("salsa") -> append("Salsa " + title(proteinName))
                    else -> append(title(sauceName) + " " + title(proteinName))
                }
            } else {
                append(title(proteinName) + " and " + title(starchName))
            }
            if (method == "sheet pan") append(" Sheet-Pan Dinner")
            else if (method == "one pot") append(" One-Pot Meal")
            else if (method == "slow cooker") append(" Slow-Cooker Meal")
        }

        val totalCost = (proteinCost + starchCost + vegCost +
            (sauce?.second ?: 0.0) + if (dairyName != null) 2.5 else 0.0)
        val servings = 4
        val prep = when (method) {
            "slow cooker" -> 10
            else -> 12
        }
        val cook = when (method) {
            "slow cooker" -> 240
            "sheet pan" -> 28
            else -> 18
        }

        val ingredients = buildList {
            add(Ingredient(name = proteinName, amount = "1 lb", brand = "store brand or your favorite"))
            add(Ingredient(name = starchName, amount = if (norm(starchName).contains("rice")) "1 cup dry" else "half a package", brand = "store brand"))
            add(Ingredient(name = vegName, amount = if (norm(vegName).startsWith("frozen")) "1 bag" else "2 cups, chopped", brand = "store brand or fresh"))
            sauce?.let { add(Ingredient(name = it.first, amount = "1 jar or can", brand = "your favorite brand")) }
            dairyName?.let { add(Ingredient(name = it, amount = "1 cup", brand = "store brand")) }
            add(Ingredient(name = "salt", amount = "to taste", brand = null))
            add(Ingredient(name = "black pepper", amount = "to taste", brand = null))
            add(Ingredient(name = "cooking oil", amount = "1 tbsp", brand = null))
        }

        val steps = when (method) {
            "slow cooker" -> listOf(
                "Warm 1 tbsp oil in a skillet over medium heat and brown the ${proteinName} on both sides, about 3–4 minutes per side.",
                "Add the ${starchName}, ${vegName}, and ${sauce?.first ?: "1 cup water"} to the slow cooker and stir to combine.",
                "Nestle the browned ${proteinName} on top, cover, and cook on low for 4 hours.",
                "Stir in the ${dairyName ?: "seasoning"} in the last 10 minutes, taste, and adjust salt and pepper.",
                "Rest 5 minutes, then serve warm straight from the pot.",
            )
            "sheet pan" -> listOf(
                "Heat the oven to 425°F and line a large sheet pan with foil.",
                "Toss the ${vegName} with 1 tbsp oil, salt, and pepper, and spread on one half of the pan.",
                "Season the ${proteinName} and place it on the other half; roast for 20 minutes.",
                "Meanwhile, cook the ${starchName} according to the package.",
                "Flip the protein, add the ${sauce?.first ?: "a squeeze of lemon"} if using, and roast 8 more minutes until cooked through.",
                "Serve the protein and veggies over the ${starchName}.",
            )
            "one pot" -> listOf(
                "Heat 1 tbsp oil in a large pot or deep skillet over medium-high heat.",
                "Brown the ${proteinName} for 3–4 minutes, then add the ${vegName} and cook 2 minutes.",
                "Add the ${starchName} and ${sauce?.first ?: "2 cups broth"} — just enough liquid to cover.",
                "Cover, reduce to a simmer, and cook until the ${starchName} is tender, about 15–18 minutes.",
                "Stir in the ${dairyName ?: "seasoning"}, taste, and adjust salt and pepper.",
                "Serve straight from the pot with a sprinkle of pepper.",
            )
            else -> listOf(
                "Warm 1 tbsp oil in a large skillet over medium-high heat.",
                "Season the ${proteinName} and brown on both sides, about 4 minutes per side; set aside.",
                "Add the ${vegName} to the same skillet and cook 3–4 minutes until crisp-tender.",
                "Return the ${proteinName} to the pan, add the ${sauce?.first ?: "1/4 cup water"} and ${dairyName ?: "a pinch of salt"} if using, and simmer 5 minutes.",
                "Cook the ${starchName} while the sauce simmers, then serve everything together.",
            )
        }

        val description = "A brand-new ${method} dinner built from what you already have: " +
            "${proteinName}, ${starchName}, and ${vegName} in about ${prep + cook} minutes — " +
            "roughly ${Money.fmt(totalCost)} for the whole family."

        val recipe = Recipe(
            id = "gen-" + Math.abs(((seedSum % 900000) + 100000).toInt()),
            name = dishName,
            description = description,
            section = "under_20",
            mealType = "Dinner",
            tags = listOf("ai-generated", "pantry", method),
            prepMinutes = prep,
            cookMinutes = cook,
            servings = servings,
            costDollars = (totalCost * 100).toInt() / 100.0,
            difficulty = "Easy",
            ingredients = ingredients,
            steps = steps,
            imageSeed = proteinName,
            featured = false,
        )

        val missing = listOf(
            if (protein == null) "protein (chicken, beef, beans, or eggs)" else null,
            if (starch == null) "a starch (rice, pasta, potatoes, or tortillas)" else null,
            if (veg == null) "a vegetable (fresh or frozen)" else null,
        ).filterNotNull()

        return GeneratedRecipe(
            recipe = recipe,
            usedItems = used,
            missingItems = missing,
            estimatedCost = totalCost,
        )
    }
}
