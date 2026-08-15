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
    /** Concrete items to add to the shopping list: (name, category, label). */
    val missingToBuy: List<Triple<String, String, String>> = emptyList(),
    val estimatedCost: Double,
)

/**
 * On-device recipe generator ("pocket AI").
 *
 * Composes genuinely NEW recipes — not picks from the bundled catalog — by
 * matching what the user actually has in their pantry to real cooking
 * techniques: a protein + starch + vegetable + sauce, prepared by one of 8
 * methods and finished in one of 8 flavor directions (64 distinct blueprints),
 * while rotating through the *user's own* pantry items so "Try another" rolls
 * a genuinely different dish — never just a renamed variation of the last one.
 *
 * Deterministic, fully offline, no API keys. Every generated recipe carries
 * honest step-by-step instructions and a cost estimate. The same pantry +
 * variant always yields the same recipe (stable and testable).
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

    /** Seasonings / aromatics the generator can spot in the pantry and use. */
    private val SEASONINGS = setOf(
        "garlic", "onion", "italian seasoning", "garlic powder", "onion powder",
        "paprika", "smoked paprika", "chili powder", "cumin", "oregano",
        "basil", "parsley", "cilantro", "thyme", "rosemary", "ginger",
        "taco seasoning", "curry powder", "bay leaf",
    )

    private fun norm(s: String): String = s.lowercase(Locale.US).trim()

    /** Match a pantry item to a known ingredient class, longest-key-first. */
    private fun matchItem(name: String, table: Map<String, Double>): String? =
        table.keys
            .filter { norm(it) in norm(name) || norm(name) in norm(it) }
            .maxByOrNull { it.length }

    /**
     * Every pantry item that matches a class, in pantry order (deduped), so a
     * user with several proteins/veggies gets them all considered — and
     * different variants can lead with different ones.
     */
    private fun collectMatches(items: List<PantryItem>, table: Map<String, Double>): List<Pair<String, Double>> {
        val out = linkedMapOf<String, Double>()
        for (i in items) {
            val hit = matchItem(i.name, table) ?: continue
            if (hit !in out) out[hit] = table.getValue(hit)
        }
        return out.toList()
    }

    /** Seasonings actually present in the pantry, in pantry order. */
    private fun pantrySeasonings(items: List<PantryItem>): List<String> {
        val found = linkedSetOf<String>()
        for (i in items) {
            val n = norm(i.name)
            if (n in STAPLES) continue
            val hit = SEASONINGS.firstOrNull { norm(it) in n || n in norm(it) } ?: continue
            found.add(hit)
        }
        return found.toList()
    }

    /** The 8 cooking methods — the backbone of every generated dish. */
    private val METHODS = listOf(
        "skillet", "sheet pan", "one pot", "slow cooker",
        "stir-fry", "casserole", "taco bowl", "soup",
    )

    /**
     * 8 flavor directions. When the pantry has its own sauce (salsa, soy,
     * marinara, ...) that sauce wins and drives the name; otherwise the
     * flavor's default sauce and seasonings give the dish its character.
     */
    private data class Flavor(
        val label: String,
        val defaultSauce: String?,
        val seasonings: String,
        val finisher: String,
    )

    private val FLAVORS = listOf(
        Flavor("Salsa", "salsa", "taco seasoning", "shredded cheese"),
        Flavor("Marinara", "pasta sauce", "Italian seasoning", "parmesan"),
        Flavor("Soy-Glazed", "soy sauce", "soy sauce and a little ginger", "green onion"),
        Flavor("Teriyaki", "teriyaki", "teriyaki sauce", "sesame seeds"),
        Flavor("BBQ", "bbq sauce", "smoked paprika", "pickled jalapeños"),
        Flavor("Creamy", "cream of chicken", "garlic powder", "sour cream"),
        Flavor("Curry", "curry paste", "curry powder", "yogurt"),
        Flavor("Lemon Herb", null, "lemon juice and dried herbs", "parmesan"),
    )

    private fun title(s: String): String =
        s.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    private fun methodLabel(method: String): String = when (method) {
        "sheet pan" -> "Sheet-Pan Dinner"
        "one pot" -> "One-Pot Meal"
        "slow cooker" -> "Slow-Cooker Meal"
        "stir-fry" -> "Stir-Fry"
        "casserole" -> "Casserole"
        "taco bowl" -> "Taco Bowl"
        "soup" -> "Soup"
        else -> "Skillet"
    }

    /**
     * Generate a new dinner recipe from the pantry. The same pantry + variant
     * always yields the same recipe (stable and testable). Variants walk
     * through 64 distinct (method × flavor) blueprints and rotate which of the
     * user's own items leads each role, so "Try another" gives something new
     * for dozens of rolls — never the same dish renamed.
     */
    fun generate(items: List<PantryItem>, focus: String = "balanced", variant: Int = 0): GeneratedRecipe {
        val pantry = items.distinctBy { norm(it.name) }

        // Every matching pantry item per role, in pantry order.
        val proteins = collectMatches(pantry, PROTEINS)
        val starches = collectMatches(pantry, STARCHES)
        val veggies = collectMatches(pantry, VEGGIES)
        val sauces = collectMatches(pantry, SAUCES)
        val dairyNames = pantry.map { it.name }.filter { n -> DAIRY.any { norm(it) in norm(n) } }
        val seasonings = pantrySeasonings(pantry)

        // Blueprint: a deterministic scramble (Knuth multiplicative hash) of
        // the variant, so consecutive "Try another" taps jump across methods,
        // flavors, AND the user's own pantry items at once — the first several
        // rolls are all genuinely different dishes, never the same one renamed.
        val scrambled = Math.floorMod(variant.toLong() * 2654435761L, 1L shl 31).toInt()
        val methodIdx = Math.floorMod(scrambled, METHODS.size)
        val flavorIdx = Math.floorMod(scrambled / METHODS.size, FLAVORS.size)
        val itemRot = Math.floorMod(scrambled / (METHODS.size * FLAVORS.size), 8)
        val method = METHODS[methodIdx]
        val flavor = FLAVORS[flavorIdx]

        fun pick(list: List<Pair<String, Double>>): Pair<String, Double>? =
            if (list.isEmpty()) null else list[((itemRot % list.size) + list.size) % list.size]

        val protein = pick(proteins)
        val starch = pick(starches)
        val veg = pick(veggies)
        // A second vegetable when the user has more than one — more of what you
        // already have, and another reason the dish differs from the last roll.
        val veg2 = if (veggies.size >= 2) pick(veggies.filter { it != veg }) else null

        // Sauce: the user's own pantry sauces win, rotating through them per
        // flavor tier so salsa -> soy -> bbq all get their turn; only fall back
        // to the flavor's default when the pantry has none.
        val pantrySauce = if (sauces.isNotEmpty())
            sauces[((flavorIdx + itemRot) % sauces.size + sauces.size) % sauces.size]
        else null
        val sauce = pantrySauce ?: flavor.defaultSauce?.let { d -> SAUCES[d]?.let { d to it } }

        // Dairy + seasonings from the pantry when present.
        val dairyName = if (dairyNames.isNotEmpty()) dairyNames[((itemRot % dairyNames.size) + dairyNames.size) % dairyNames.size] else null
        val seasoning = if (seasonings.isNotEmpty()) seasonings[((itemRot % seasonings.size) + seasonings.size) % seasonings.size] else null

        val proteinName = protein?.first ?: "canned beans"
        val proteinCost = protein?.second ?: 2.0
        val starchName = starch?.first ?: "rice"
        val starchCost = starch?.second ?: 2.5
        val vegName = veg?.first ?: "frozen vegetables"
        val vegCost = veg?.second ?: 2.0
        val sauceName = sauce?.first

        // "Uses" is strictly what the user already had — a default sauce the
        // recipe suggests is listed as a step ingredient, never claimed as yours.
        val used = buildList {
            protein?.first?.let { add(it) }
            starch?.first?.let { add(it) }
            veg?.first?.let { add(it) }
            veg2?.first?.let { add(it) }
            pantrySauce?.first?.let { add(it) }
            dairyName?.let { add(it) }
            seasoning?.let { add(it) }
        }

        // Deterministic seed so the same pantry + variant is stable.
        val seedSum = pantry.sumOf { norm(it.name).hashCode().toLong() } + variant.toLong() * 7919L

        // Dish name: flavor + protein + style, with the starch/veg woven in so
        // every blueprint reads as its own dish, not a renamed copy.
        val flavorLead = sauceName?.let { title(it) } ?: flavor.label
        val style = methodLabel(method)
        val dishName = buildString {
            append(flavorLead)
            append(" ")
            append(title(proteinName))
            append(" ")
            append(style)
            append(" with ")
            append(title(starchName))
            append(" and ")
            append(title(vegName))
            veg2?.let { append(", "); append(title(it.first)) }
        }

        val totalCost = proteinCost + starchCost + vegCost +
            (veg2?.second ?: 0.0) +
            (sauce?.second ?: 0.0) +
            if (dairyName != null) 2.5 else 0.0
        val servings = 4
        val prep = if (method == "slow cooker" || method == "soup") 10 else 12
        val cook = when (method) {
            "slow cooker" -> 240
            "sheet pan", "casserole" -> 32
            "stir-fry" -> 15
            "taco bowl" -> 20
            "soup" -> 35
            else -> 18
        }

        val ingredients = buildList {
            add(Ingredient(name = proteinName, amount = "1 lb", brand = "store brand or your favorite"))
            add(Ingredient(name = starchName, amount = if (norm(starchName).contains("rice")) "1 cup dry" else "half a package", brand = "store brand"))
            add(Ingredient(name = vegName, amount = if (norm(vegName).startsWith("frozen")) "1 bag" else "2 cups, chopped", brand = "store brand or fresh"))
            veg2?.let { add(Ingredient(name = it.first, amount = if (norm(it.first).startsWith("frozen")) "1 bag" else "2 cups, chopped", brand = "store brand or fresh")) }
            sauce?.let { add(Ingredient(name = it.first, amount = "1 jar or can", brand = "your favorite brand")) }
            dairyName?.let { add(Ingredient(name = it, amount = "1 cup", brand = "store brand")) }
            seasoning?.let { add(Ingredient(name = it, amount = "1 tsp", brand = null)) }
            add(Ingredient(name = "salt", amount = "to taste", brand = null))
            add(Ingredient(name = "black pepper", amount = "to taste", brand = null))
            add(Ingredient(name = "cooking oil", amount = "1 tbsp", brand = null))
        }

        val seasoningUse = seasoning ?: flavor.seasonings
        val finisher = dairyName ?: flavor.finisher
        val sauceWord = sauceName ?: "1 cup water"

        val steps = when (method) {
            "slow cooker" -> listOf(
                "Warm 1 tbsp oil in a skillet over medium heat and brown the ${proteinName} on both sides, about 3–4 minutes per side.",
                "Add the ${starchName}, ${vegName}${veg2?.let { ", ${it.first}" } ?: ""}, ${sauceWord}, and ${seasoningUse} to the slow cooker and stir to combine.",
                "Nestle the browned ${proteinName} on top, cover, and cook on low for 4 hours.",
                "Stir in the ${finisher} in the last 10 minutes, taste, and adjust salt and pepper.",
                "Rest 5 minutes, then serve warm straight from the pot.",
            )
            "sheet pan" -> listOf(
                "Heat the oven to 425°F and line a large sheet pan with foil.",
                "Toss the ${vegName}${veg2?.let { " and ${it.first}" } ?: ""} with 1 tbsp oil, salt, pepper, and ${seasoningUse}, and spread on one half of the pan.",
                "Season the ${proteinName} and place it on the other half; roast for 20 minutes.",
                "Meanwhile, cook the ${starchName} according to the package.",
                "Flip the protein, add the ${sauceWord} if using, and roast 8 more minutes until cooked through.",
                "Serve the protein and veggies over the ${starchName}, finished with ${finisher}.",
            )
            "one pot" -> listOf(
                "Heat 1 tbsp oil in a large pot or deep skillet over medium-high heat.",
                "Brown the ${proteinName} for 3–4 minutes, then add the ${vegName}${veg2?.let { " and ${it.first}" } ?: ""} and cook 2 minutes.",
                "Add the ${starchName}, ${sauceWord}, and ${seasoningUse} — just enough liquid to cover.",
                "Cover, reduce to a simmer, and cook until the ${starchName} is tender, about 15–18 minutes.",
                "Stir in the ${finisher}, taste, and adjust salt and pepper.",
                "Serve straight from the pot with a sprinkle of pepper.",
            )
            "stir-fry" -> listOf(
                "Heat 1 tbsp oil in a wok or large skillet over high heat until shimmering.",
                "Stir-fry the ${proteinName} for 3–4 minutes until just cooked; remove and set aside.",
                "Add the ${vegName}${veg2?.let { " and ${it.first}" } ?: ""} and stir-fry 2–3 minutes, keeping them crisp.",
                "Return the protein, add the ${sauceWord} and ${seasoningUse}, and toss 1 minute until glossy.",
                "Cook the ${starchName} while the sauce glazes, then serve everything together, finished with ${finisher}.",
            )
            "casserole" -> listOf(
                "Heat the oven to 375°F and lightly grease a 9×13 baking dish.",
                "Brown the ${proteinName} in 1 tbsp oil over medium heat, about 4 minutes.",
                "Combine the ${proteinName}, ${starchName}, ${vegName}${veg2?.let { ", ${it.first}" } ?: ""}, ${sauceWord}, and ${seasoningUse} in the dish and stir.",
                "Cover with foil and bake 25 minutes; uncover, top with ${finisher}, and bake 10 more minutes until bubbly.",
                "Rest 5 minutes before serving straight from the dish.",
            )
            "taco bowl" -> listOf(
                "Warm 1 tbsp oil in a skillet over medium heat.",
                "Cook the ${proteinName} with ${seasoningUse} until browned and cooked through, about 5–6 minutes.",
                "While it cooks, prepare the ${starchName} and warm the ${vegName}${veg2?.let { " and ${it.first}" } ?: ""}.",
                "Build bowls: ${starchName} on the bottom, then the ${proteinName}, then the veggies, a spoonful of ${sauceWord}, and ${finisher} on top.",
                "Serve warm with lime or hot sauce if you like.",
            )
            "soup" -> listOf(
                "Warm 1 tbsp oil in a large pot over medium heat.",
                "Brown the ${proteinName} for 3–4 minutes, then add the ${vegName}${veg2?.let { " and ${it.first}" } ?: ""} and cook 2 minutes.",
                "Add the ${starchName}, ${sauceWord}, ${seasoningUse}, and 4 cups water or broth — bring to a simmer.",
                "Simmer, covered, until everything is tender, about 20–25 minutes.",
                "Stir in the ${finisher}, taste, and adjust salt and pepper before serving.",
            )
            else -> listOf(
                "Warm 1 tbsp oil in a large skillet over medium-high heat.",
                "Season the ${proteinName} with ${seasoningUse} and brown on both sides, about 4 minutes per side; set aside.",
                "Add the ${vegName}${veg2?.let { " and ${it.first}" } ?: ""} to the same skillet and cook 3–4 minutes until crisp-tender.",
                "Return the ${proteinName} to the pan, add the ${sauceWord} and ${finisher} if using, and simmer 5 minutes.",
                "Cook the ${starchName} while the sauce simmers, then serve everything together.",
            )
        }

        val description = "A brand-new ${method} ${flavor.label.lowercase(Locale.US)} dinner built from what you already have: " +
            "${proteinName}, ${starchName}, and ${vegName} in about ${prep + cook} minutes — " +
            "roughly ${Money.fmt(totalCost)} for the whole family."

        val recipe = Recipe(
            id = "gen-" + Math.abs(((seedSum % 900000) + 100000).toInt()),
            name = dishName,
            description = description,
            section = "under_20",
            mealType = "Dinner",
            tags = listOf("ai-generated", "pantry", method, flavor.label.lowercase(Locale.US)),
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

        // Concrete missing ingredients the user could actually buy — with the
        // friendly "what to look for" label for the UI and the concrete name
        // for one-tap shopping-list adds.
        data class Missing(val label: String, val name: String, val category: String)
        val missing = buildList {
            if (protein == null) add(Missing("protein (chicken, beef, beans, or eggs)", "chicken breast", "Meat"))
            if (starch == null) add(Missing("a starch (rice, pasta, potatoes, or tortillas)", "rice", "Grocery"))
            if (veg == null) add(Missing("a vegetable (fresh or frozen)", "broccoli", "Produce"))
        }

        return GeneratedRecipe(
            recipe = recipe,
            usedItems = used,
            missingItems = missing.map { it.label },
            missingToBuy = missing.map { Triple(it.name, it.category, it.label) },
            estimatedCost = totalCost,
        )
    }
}
