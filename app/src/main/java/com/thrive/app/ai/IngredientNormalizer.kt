package com.thrive.app.ai

import com.thrive.app.data.model.Recipe
import java.util.Locale

/**
 * One normalized shopping line: the canonical ingredient, how much of it the
 * plan needs (in a practical display unit), and what it costs (estimated).
 */
data class PlanIngredientLine(
    val name: String,          // canonical display name ("chicken breast")
    val quantity: Double,      // in [unit] scale (recipe consumption)
    val unit: String,          // "lb", "cup", "can", "each", ...
    val estCost: Double,       // estimated cost of the needed quantity (consumption)
    val recipes: Int,          // how many planned recipes use it (reuse signal)
    val haveInPantry: Boolean = false,
    // Package-aware cart fields: what you actually buy and pay. Recipe
    // quantities are fractions, but customers buy packages/counter cuts —
    // 0.5 lb becomes a 1 lb purchase. cartCost keeps the same estimated unit
    // price as estCost, scaled to the rounded quantity, so the register total
    // is honest without inventing new prices.
    val cartQty: Double = 0.0,
    val cartCost: Double = 0.0,
) {
    val label: String
        get() = if (quantity >= 2 || quantity == 0.0) "${trimQty(quantity)} $unit" else "1 $unit"

    private fun trimQty(q: Double): String =
        if (q == q.toInt().toDouble()) q.toInt().toString() else (Math.round(q * 10) / 10.0).toString()
}

/** A shopping list grouped by store aisle so the list is easy to walk. */
data class PlanShoppingGroup(
    val category: String,
    val items: List<PlanIngredientLine>,
    val subtotal: Double,
    // What the aisle actually costs at the register (package-rounded).
    val cartSubtotal: Double = subtotal,
)

/**
 * Turns recipe ingredients into a consolidated, aisle-grouped shopping list:
 *  - canonical names merge "1 onion" / "one yellow onion" / "2 onions";
 *  - quantities are summed and converted within the same unit family
 *    (oz + lb → lb; tbsp + cup → cup) and never merged across families
 *    (canned vs fresh tomatoes stay separate — honest, not misleading);
 *  - pantry items are subtracted: the user already owns them;
 *  - every cost is labeled an estimate (no fabricated store prices).
 * Pure Kotlin, deterministic, offline.
 */
object IngredientNormalizer {

    private val LOCALE = Locale.US

    /** Aliases that mean the same shopping purchase. */
    private val ALIASES = mapOf(
        "yellow onion" to "onion", "white onion" to "onion", "sweet onion" to "onion",
        "red onion" to "onion", "brown onion" to "onion", "1 medium onion" to "onion",
        "onions" to "onion",
        "ground beef" to "ground beef", "hamburger meat" to "ground beef", "mince" to "ground beef",
        "lean ground beef" to "ground beef",
        "boneless skinless chicken breast" to "chicken breast", "chicken breasts" to "chicken breast",
        "chicken breast halves" to "chicken breast", "chicken breast" to "chicken breast",
        "chicken thighs" to "chicken thighs", "boneless chicken thighs" to "chicken thighs",
        "chicken" to "chicken",
        "bell peppers" to "bell pepper", "peppers" to "bell pepper", "red bell pepper" to "bell pepper",
        "green bell pepper" to "bell pepper",
        "garlic cloves" to "garlic", "cloves garlic" to "garlic", "garlic clove" to "garlic",
        "scallions" to "green onion", "green onions" to "green onion",
        "cherry tomatoes" to "tomatoes", "roma tomatoes" to "tomatoes", "tomato" to "tomatoes",
        "canned diced tomatoes" to "canned tomatoes", "diced tomatoes" to "canned tomatoes",
        "crushed tomatoes" to "canned tomatoes",
        "spaghetti" to "pasta", "penne" to "pasta", "macaroni" to "pasta", "linguine" to "pasta",
        "fettuccine" to "pasta", "rigatoni" to "pasta", "rotini" to "pasta",
        "white rice" to "rice", "long grain rice" to "rice", "jasmine rice" to "rice",
        "basmati rice" to "rice", "brown rice" to "brown rice",
        "shredded cheddar" to "cheddar cheese", "cheddar" to "cheddar cheese",
        "shredded mozzarella" to "mozzarella cheese", "mozzarella" to "mozzarella cheese",
        "parmesan" to "parmesan cheese", "grated parmesan" to "parmesan cheese",
        "sour cream" to "sour cream", "plain yogurt" to "yogurt", "greek yogurt" to "yogurt",
        "whole milk" to "milk", "2% milk" to "milk",
        "olive oil" to "olive oil", "extra virgin olive oil" to "olive oil",
        "vegetable oil" to "cooking oil", "canola oil" to "cooking oil",
        "soy sauce" to "soy sauce", "low sodium soy sauce" to "soy sauce",
        "canned black beans" to "black beans", "black beans" to "black beans",
        "canned kidney beans" to "kidney beans", "kidney beans" to "kidney beans",
        "canned chickpeas" to "chickpeas", "chickpeas" to "chickpeas", "garbanzo beans" to "chickpeas",
        "frozen peas" to "peas", "frozen corn" to "corn",
        "frozen broccoli" to "broccoli", "broccoli florets" to "broccoli",
        "baby spinach" to "spinach", "fresh spinach" to "spinach",
        "cooked rice" to "rice", "cooked pasta" to "pasta",
        "ground turkey" to "ground turkey", "ground chicken" to "ground chicken",
        "whole wheat bread" to "bread", "sandwich bread" to "bread",
        "flour tortillas" to "tortillas", "corn tortillas" to "corn tortillas",
        "chicken broth" to "chicken broth", "low sodium chicken broth" to "chicken broth",
        "vegetable broth" to "vegetable broth", "beef broth" to "beef broth",
        "tomato sauce" to "tomato sauce", "marinara" to "pasta sauce", "marinara sauce" to "pasta sauce",
        "spaghetti sauce" to "pasta sauce",
        "heavy cream" to "heavy cream", "whipping cream" to "heavy cream",
        "butter" to "butter", "unsalted butter" to "butter",
        "eggs" to "eggs", "large eggs" to "eggs",
        "lemons" to "lemon", "limes" to "lime",
        "potatoes" to "potatoes", "russet potatoes" to "potatoes", "yukon gold potatoes" to "potatoes",
        "sweet potatoes" to "sweet potatoes",
        "carrots" to "carrots", "baby carrots" to "carrots",
        "zucchini" to "zucchini", "yellow squash" to "zucchini",
        "mushrooms" to "mushrooms", "cremini mushrooms" to "mushrooms",
        "ketchup" to "ketchup", "mustard" to "mustard", "dijon mustard" to "dijon mustard",
        "mayonnaise" to "mayonnaise", "mayo" to "mayonnaise",
        "honey" to "honey", "maple syrup" to "maple syrup",
        "rolled oats" to "rolled oats", "quick oats" to "rolled oats", "oats" to "rolled oats",
        "bacon" to "bacon", "bacon strips" to "bacon",
        "italian sausage" to "italian sausage", "sausage" to "sausage",
        "ground pork" to "ground pork",
        "canned tuna" to "canned tuna", "tuna" to "canned tuna",
        "salmon fillets" to "salmon", "salmon" to "salmon",
        "shrimp" to "shrimp", "shrimp (peeled)" to "shrimp",
        "frozen shrimp" to "shrimp",
        "salsa" to "salsa", "pico de gallo" to "salsa",
        "taco seasoning" to "taco seasoning",
        "chili powder" to "chili powder", "cumin" to "cumin", "paprika" to "paprika",
        "italian seasoning" to "italian seasoning", "oregano" to "oregano",
        "bay leaf" to "bay leaves", "bay leaves" to "bay leaves",
        "cilantro" to "cilantro", "fresh cilantro" to "cilantro",
        "parsley" to "parsley", "fresh parsley" to "parsley",
        "basil" to "basil", "fresh basil" to "basil",
        "thyme" to "thyme", "fresh thyme" to "thyme",
        "rosemary" to "rosemary", "fresh rosemary" to "rosemary",
        "ginger" to "ginger", "fresh ginger" to "ginger", "ginger root" to "ginger",
        "vanilla extract" to "vanilla extract",
        "baking powder" to "baking powder", "baking soda" to "baking soda",
        "flour" to "flour", "all-purpose flour" to "flour",
        "sugar" to "sugar", "granulated sugar" to "sugar",
        "brown sugar" to "brown sugar", "packed brown sugar" to "brown sugar",
        "cocoa powder" to "cocoa powder", "chocolate chips" to "chocolate chips",
        "peanut butter" to "peanut butter", "jelly" to "jelly",
        "apple cider vinegar" to "apple cider vinegar", "white vinegar" to "white vinegar",
        "red wine vinegar" to "red wine vinegar", "balsamic vinegar" to "balsamic vinegar",
        "worcestershire sauce" to "worcestershire sauce",
        "hot sauce" to "hot sauce", "sriracha" to "sriracha", "bbq sauce" to "bbq sauce",
        "teriyaki sauce" to "teriyaki sauce", "buffalo sauce" to "buffalo sauce",
        "coconut milk" to "coconut milk", "curry paste" to "curry paste",
        "cream cheese" to "cream cheese", "ricotta" to "ricotta", "cottage cheese" to "cottage cheese",
        "feta" to "feta cheese", "feta cheese" to "feta cheese",
        "shredded cheese" to "shredded cheese",
        "avocado" to "avocado", "cucumber" to "cucumber", "lettuce" to "lettuce",
        "romaine" to "lettuce", "iceberg lettuce" to "lettuce", "salad greens" to "lettuce",
        "cabbage" to "cabbage", "kale" to "kale",
        "green beans" to "green beans", "asparagus" to "asparagus",
        "cauliflower" to "cauliflower", "brussels sprouts" to "brussels sprouts",
        "eggplant" to "eggplant", "beets" to "beets", "squash" to "squash",
        "frozen vegetables" to "frozen vegetables", "mixed vegetables" to "frozen vegetables",
        "frozen mixed vegetables" to "frozen vegetables",
        "green onions" to "green onion", "scallions" to "green onion",
    )

    /** Deterministic aisle assignment for canonical names. */
    fun categorize(name: String): String {
        val n = name.lowercase(LOCALE).trim()
        val produce = listOf("onion", "garlic", "potato", "carrot", "broccoli", "spinach", "lettuce",
            "tomato", "pepper", "zucchini", "mushroom", "celery", "peas", "corn", "green bean",
            "cauliflower", "kale", "cabbage", "squash", "asparagus", "eggplant", "beet",
            "cucumber", "avocado", "lemon", "lime", "apple", "banana", "berry", "blueberr",
            "strawberr", "grape", "orange", "peach", "mango", "pineapple", "watermelon",
            "cilantro", "parsley", "basil", "thyme", "rosemary", "dill", "chive", "mint",
            "ginger", "scallion", "green onion", "sweet potato", "shallot", "arugula", "chard",
            "endive", "radish", "turnip", "artichoke", "okra")
        val meat = listOf("chicken", "beef", "pork", "turkey", "bacon", "sausage", "ham", "lamb",
            "steak", "ground", "meatball", "deli", "shrimp", "salmon", "fish", "tuna", "tilapia",
            "cod", "crab", "lobster", "scallop", "tofu")
        val dairy = listOf("milk", "cheese", "butter", "cream", "yogurt", "egg", "sour cream",
            "ricotta", "cottage", "feta", "paneer", "half and half", "mozzarella", "cheddar", "parmesan")
        val bakery = listOf("bread", "bagel", "tortilla", "roll", "bun", "croissant", "pita", "naan",
            "muffin", "pancake mix", "english muffin")
        val frozen = listOf("frozen", "ice cream", "pizza")
        val canned = listOf("canned", "jar", "tomato sauce", "tomato paste", "broth", "soup",
            "beans", "chickpea", "lentil", "tuna", "salsa", "pasta sauce", "marinara")
        val spices = listOf("salt", "pepper", "paprika", "cumin", "chili", "oregano", "basil leaf",
            "thyme leaf", "seasoning", "cinnamon", "nutmeg", "garlic powder", "onion powder",
            "vanilla", "baking powder", "baking soda", "bay leaf", "curry", "turmeric", "cayenne",
            "clove", "allspice", "dill", "rosemary", "sage", "parsley", "tarragon", "za'atar",
            "adobo", "old bay")
        val beverages = listOf("juice", "soda", "tea", "coffee", "water", "beer", "wine",
            "lemonade", "drink", "oat milk", "almond milk")
        val pantry = listOf("rice", "pasta", "flour", "sugar", "oats", "oil", "vinegar",
            "soy sauce", "hot sauce", "bbq", "teriyaki", "ketchup", "mustard", "mayonnaise",
            "honey", "syrup", "cereal", "cracker", "chip", "snack", "cookie", "granola",
            "peanut butter", "jelly", "worcestershire", "hoisin", "oyster sauce", "sriracha",
            "coconut milk", "curry paste", "bouillon", "stock")
        fun hit(list: List<String>): Boolean = list.any { n.contains(it) }
        return when {
            hit(produce) -> "Produce"
            hit(meat) -> "Meat & Seafood"
            hit(dairy) -> "Dairy & Eggs"
            hit(bakery) -> "Bakery"
            hit(frozen) -> "Frozen"
            hit(canned) -> "Canned Goods"
            hit(spices) -> "Spices & Seasonings"
            hit(beverages) -> "Beverages"
            hit(pantry) -> "Pantry"
            else -> "Other"
        }
    }

    /** Practical purchase note per aisle (honest guidance, not a price claim). */
    fun packageHint(category: String): String? = when (category) {
        "Produce" -> "sold by the lb / bunch"
        "Meat & Seafood" -> "sold by the lb"
        "Dairy & Eggs" -> "per carton / container"
        "Bakery" -> "per loaf / package"
        "Frozen", "Canned Goods", "Pantry" -> "per package / can"
        "Spices & Seasonings" -> "small jar"
        else -> null
    }

    /**
     * Parses a recipe amount like "1 lb", "2 cups", "½ can", "4" into
     * (quantity, unit). Returns null when the amount can't be read.
     */
    fun parseAmount(amount: String): Pair<Double, String>? {
        val a = (amount ?: "").trim()
        if (a.isEmpty()) return null
        val frac = mapOf("½" to 0.5, "¼" to 0.25, "¾" to 0.75, "⅓" to 1.0 / 3.0, "⅔" to 2.0 / 3.0, "⅛" to 0.125)
        var qty: Double? = null
        for ((k, v) in frac) if (a.contains(k)) qty = v
        val slash = Regex("""(\d+)\s*/\s*(\d+)""").find(a)
        if (slash != null) {
            val num = slash.groupValues[1].toDoubleOrNull() ?: 0.0
            val den = slash.groupValues[2].toDoubleOrNull() ?: 1.0
            qty = if (den != 0.0) num / den else null
        }
        val number = Regex("""(\d+(?:\.\d+)?)""").find(a)
        val baseQty = number?.groupValues?.get(1)?.toDoubleOrNull()
        if (qty == null && baseQty != null) qty = baseQty
        val unitMatch = Regex("""([a-z]+)\s*$""", RegexOption.IGNORE_CASE).find(a.trim())
        val unit = unitMatch?.groupValues?.get(1)?.lowercase(LOCALE) ?: "each"
        return (qty ?: 1.0) to normalizeUnit(unit)
    }

    private fun normalizeUnit(u: String): String = when (u) {
        "lbs", "pound", "pounds" -> "lb"
        "oz", "ounce", "ounces" -> "oz"
        "cups", "cup" -> "cup"
        "tbsp", "tbs", "tablespoon", "tablespoons" -> "tbsp"
        "tsp", "teaspoon", "teaspoons" -> "tsp"
        "cans", "can" -> "can"
        "jars", "jar" -> "jar"
        "cloves", "clove" -> "clove"
        "bags", "bag" -> "bag"
        "boxes", "box" -> "box"
        "packages", "package", "packs", "pack" -> "package"
        "stalks", "stalk" -> "stalk"
        "bunches", "bunch" -> "bunch"
        "heads", "head" -> "head"
        "slices", "slice" -> "slice"
        "pieces", "piece" -> "piece"
        "filets", "fillet" -> "fillet"
        "gallons", "gallon" -> "gal"
        "quarts", "quart" -> "qt"
        "pints", "pint" -> "pt"
        "grams", "g" -> "g"
        "kilograms", "kg" -> "kg"
        "liters", "liter", "litres", "litre" -> "l"
        "milliliters", "ml" -> "ml"
        else -> u
    }

    /**
     * Rounds a needed quantity up to the practical purchase size
     * (package-aware): weight rounds to the next 0.5 lb with a floor of 0.5,
     * count/container units round up to a whole item (you buy a whole can,
     * bunch, or package), and volume stays as-is (priced per unit used).
     * Never rounds DOWN — the cart can only cost as much or more than the
     * recipe consumption estimate.
     */
    fun packageQty(qty: Double, unit: String): Double = when (unit) {
        // Weight: round up to the next half pound with a 1 lb floor — a recipe
        // needing 0.5 lb of chicken means buying a 1 lb package, and 200 g of
        // anything still means a ~1 lb counter cut.
        "lb" -> (Math.ceil(qty * 2) / 2).coerceAtLeast(1.0)
        "each", "can", "jar", "bag", "box", "package", "bunch", "head", "clove",
        "stalk", "slice", "piece", "fillet" -> Math.ceil(qty).coerceAtLeast(1.0)
        else -> qty
    }

    /** Converts a (qty, unit) into the family base for summing. */
    private fun toBase(qty: Double, unit: String): Triple<Double, String, Double>? = when (unit) {
        "lb" -> Triple(qty, "lb", 1.0)
        "oz" -> Triple(qty / 16.0, "lb", 1.0 / 16.0)
        "kg" -> Triple(qty * 2.20462, "lb", 2.20462)
        "g" -> Triple(qty * 2.20462 / 1000.0, "lb", 2.20462 / 1000.0)
        "cup" -> Triple(qty, "cup", 1.0)
        "tbsp" -> Triple(qty / 16.0, "cup", 1.0 / 16.0)
        "tsp" -> Triple(qty / 48.0, "cup", 1.0 / 48.0)
        "gal" -> Triple(qty, "gal", 1.0)
        "qt" -> Triple(qty / 4.0, "gal", 0.25)
        "pt" -> Triple(qty / 8.0, "gal", 0.125)
        "fl oz", "floz" -> Triple(qty / 128.0, "gal", 1.0 / 128.0)
        "l" -> Triple(qty * 0.264172, "gal", 0.264172)
        "ml" -> Triple(qty * 0.000264172, "gal", 0.000264172)
        "can" -> Triple(qty, "can", 1.0)
        "jar" -> Triple(qty, "jar", 1.0)
        "bag" -> Triple(qty, "bag", 1.0)
        "box" -> Triple(qty, "box", 1.0)
        "package" -> Triple(qty, "package", 1.0)
        "clove" -> Triple(qty, "clove", 1.0)
        "stalk" -> Triple(qty, "stalk", 1.0)
        "bunch" -> Triple(qty, "bunch", 1.0)
        "head" -> Triple(qty, "head", 1.0)
        "slice" -> Triple(qty, "slice", 1.0)
        "piece" -> Triple(qty, "piece", 1.0)
        "fillet" -> Triple(qty, "fillet", 1.0)
        "each" -> Triple(qty, "each", 1.0)
        else -> null
    }

    /** Canonical display name for an ingredient (aliases → one shopping item). */
    fun canonicalName(name: String): String {
        val n = name.lowercase(LOCALE).trim()
        ALIASES[n]?.let { return it }
        ALIASES.entries
            .filter { (k, _) -> n.contains(k) }
            .maxByOrNull { it.key.length }
            ?.let { return it.value }
        return n
    }

    /** True when a pantry item covers the canonical ingredient. */
    private fun pantryCovers(pantryName: String, ingredientName: String): Boolean {
        val p = pantryName.lowercase(LOCALE).trim()
        val i = ingredientName.lowercase(LOCALE).trim()
        if (p == i) return true
        if (p.contains(i) || i.contains(p)) return true
        val pTokens = p.split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()
        val iTokens = i.split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()
        return pTokens.isNotEmpty() && iTokens.isNotEmpty() &&
            pTokens.count { it in iTokens } >= minOf(2, iTokens.size)
    }

    private fun pantryLookup(pantryNames: List<String>, ingredientName: String): Boolean =
        pantryNames.any { pantryCovers(it, ingredientName) }

    /**
     * Builds the consolidated shopping list from a set of recipes scaled for
     * [servingsPerRecipe] servings each, subtracting the pantry.
     */
    fun build(
        recipes: List<Recipe>,
        pantryNames: List<String>,
        servingsPerRecipe: Int = 4,
        priceLookup: (String) -> Double = { PantryMealEngine.estimateIngredientPrice(it) },
    ): List<PlanShoppingGroup> {
        val raw = mutableListOf<PlanIngredientLine>()
        for (recipe in recipes) {
            val scale = (servingsPerRecipe.toDouble() / recipe.servings.coerceAtLeast(1)).coerceAtLeast(0.25)
            val usedCanonicals = LinkedHashSet<String>()
            for (ing in recipe.ingredients) {
                if (PantryMealEngine.isStaple(ing.name)) continue
                val canonical = canonicalName(ing.name)
                val parsed = parseAmount(ing.amount)
                val qty = (parsed?.first ?: 1.0) * scale
                val unit = parsed?.second ?: "each"
                if (canonical in usedCanonicals) continue // "1 onion, sliced" is one onion
                usedCanonicals += canonical
                raw += PlanIngredientLine(
                    name = canonical,
                    quantity = qty,
                    unit = unit,
                    estCost = Math.round(priceLookup(canonical) * scale * 100) / 100.0,
                    recipes = 1,
                )
            }
        }

        // Merge by canonical + unit family (convert to a common base unit).
        val merged = LinkedHashMap<String, PlanIngredientLine>()
        for (line in raw) {
            val existing = merged[line.name]
            if (existing == null) {
                merged[line.name] = line
                continue
            }
            val base = toBase(line.quantity, line.unit)
            val exBase = toBase(existing.quantity, existing.unit)
            if (base != null && exBase != null && base.second == exBase.second) {
                // Same family (weight/volume/count/container) — sum.
                merged[line.name] = existing.copy(
                    quantity = Math.round((base.first + exBase.first) * 100) / 100.0,
                    unit = base.second,
                    estCost = Math.round((existing.estCost + line.estCost) * 100) / 100.0,
                    recipes = existing.recipes + 1,
                )
            } else if (existing.unit == line.unit) {
                // Same literal unit (e.g. two "1 can" entries) — sum even when
                // we don't have a base conversion.
                merged[line.name] = existing.copy(
                    quantity = Math.round((existing.quantity + line.quantity) * 100) / 100.0,
                    estCost = Math.round((existing.estCost + line.estCost) * 100) / 100.0,
                    recipes = existing.recipes + 1,
                )
            }
            // Incompatible forms (cups vs cloves genuinely differ) — keep the
            // existing line; the new one is intentionally not merged.
        }

        // Pantry subtraction + package-aware cart rounding: mark what the user
        // already owns (zero cost) and round everything else up to a practical
        // purchase quantity at the same estimated unit price.
        val finalList = merged.values.map { line ->
            val has = pantryLookup(pantryNames, line.name)
            if (has) {
                line.copy(haveInPantry = true, estCost = 0.0, cartQty = line.quantity, cartCost = 0.0)
            } else {
                val cartQ = packageQty(line.quantity, line.unit)
                val cartC = if (line.quantity > 0)
                    Math.round(line.estCost * (cartQ / line.quantity) * 100) / 100.0
                else line.estCost
                line.copy(cartQty = cartQ, cartCost = cartC)
            }
        }

        // Group by aisle, in a walkable order.
        val categoryOrder = listOf("Produce", "Meat & Seafood", "Dairy & Eggs", "Bakery",
            "Canned Goods", "Frozen", "Pantry", "Spices & Seasonings", "Beverages", "Other")
        return finalList
            .groupBy { categorize(it.name) }
            .toList()
            .sortedBy { (cat, _) -> categoryOrder.indexOf(cat).let { if (it < 0) categoryOrder.size else it } }
            .map { (cat, items) ->
                PlanShoppingGroup(
                    category = cat,
                    items = items.sortedBy { it.name },
                    subtotal = Math.round(items.filterNot { it.haveInPantry }.sumOf { it.estCost } * 100) / 100.0,
                    cartSubtotal = Math.round(items.filterNot { it.haveInPantry }.sumOf { it.cartCost } * 100) / 100.0,
                )
            }
    }
}
