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
 * Reads the user's ACTUAL pantry and composes a genuinely new dish from it —
 * never a pick from the bundled catalog. It recognizes a large, real-world
 * ingredient vocabulary (brand words and modifiers are stripped, so "Great
 * Value boneless skinless chicken breast" still matches "chicken breast"),
 * then builds a protein + starch + vegetable + sauce dinner across
 * 12 cooking methods × 12 flavor directions (144 distinct blueprints),
 * weaving in the user's own herbs, dairy, citrus, and seasonings. "Try
 * another" rotates through the user's *own* items, so a well-stocked pantry
 * yields dozens of genuinely different dishes in a row.
 *
 * Deterministic, fully offline, no API keys. The same pantry + variant always
 * yields the same recipe (stable and testable).
 */
object RecipeMakerEngine {

    private val LOCALE = Locale.US

    private val PROTEINS = mapOf(
        "chicken breast" to 4.5, "chicken thighs" to 4.0, "chicken" to 4.0,
        "chicken wings" to 4.0, "chicken drumsticks" to 3.5, "ground beef" to 5.0,
        "beef" to 5.5, "ground turkey" to 4.5, "turkey breast" to 4.5,
        "pork chops" to 4.0, "pork" to 4.0, "bacon" to 4.0,
        "sausage" to 3.5, "italian sausage" to 3.5, "shrimp" to 6.0,
        "fish" to 5.0, "salmon" to 6.0, "tilapia" to 5.0, "tuna" to 1.5,
        "canned tuna" to 1.5, "eggs" to 2.5, "tofu" to 2.5, "tempeh" to 3.0,
        "black beans" to 1.5, "kidney beans" to 1.5, "chickpeas" to 1.5,
        "lentils" to 2.0, "pinto beans" to 1.5, "white beans" to 1.5,
        "ham" to 3.5, "turkey" to 4.0, "steak" to 6.0, "meatballs" to 4.5,
        "hot dogs" to 3.0, "deli turkey" to 4.5, "deli ham" to 4.0,
        "lamb" to 6.0, "corned beef" to 5.0, "brisket" to 6.5,
    )

    private val STARCHES = mapOf(
        "rice" to 2.5, "jasmine rice" to 3.0, "basmati rice" to 3.5,
        "brown rice" to 3.0, "pasta" to 1.5, "spaghetti" to 1.5, "penne" to 1.5,
        "macaroni" to 1.5, "noodles" to 2.0, "egg noodles" to 2.0, "ramen" to 1.5,
        "potatoes" to 3.0, "sweet potatoes" to 2.5, "tortillas" to 2.5,
        "flour tortillas" to 2.5, "corn tortillas" to 2.5, "bread" to 2.5,
        "rolls" to 2.5, "quinoa" to 3.5, "couscous" to 2.5, "grits" to 2.0,
        "polenta" to 2.5, "frozen fries" to 2.5, "orzo" to 2.0, "farro" to 4.0,
        "barley" to 3.0, "bulgur" to 2.5, "gnocchi" to 3.0, "naan" to 2.5,
        "pita" to 2.5, "bagels" to 2.5, "english muffins" to 2.5,
        "pancake mix" to 3.0, "stuffing mix" to 2.0, "dumplings" to 3.5,
    )

    private val VEGGIES = mapOf(
        "broccoli" to 2.0, "carrots" to 1.5, "celery" to 1.5, "spinach" to 2.5,
        "lettuce" to 2.0, "tomatoes" to 2.5, "bell pepper" to 1.5,
        "onion" to 1.0, "garlic" to 1.0, "zucchini" to 1.5, "mushrooms" to 2.0,
        "peas" to 1.5, "corn" to 1.5, "green beans" to 2.0, "cauliflower" to 2.5,
        "kale" to 2.5, "cabbage" to 2.0, "squash" to 2.0, "asparagus" to 3.0,
        "eggplant" to 2.0, "beets" to 2.0, "brussels sprouts" to 2.5,
        "cucumber" to 1.5, "leek" to 2.0, "okra" to 2.5, "parsnip" to 2.0,
        "turnip" to 2.0, "radish" to 1.5, "arugula" to 3.0, "swiss chard" to 2.5,
        "bok choy" to 2.0, "collard greens" to 2.5, "fennel" to 2.5,
        "artichoke" to 3.0, "edamame" to 2.5, "shallot" to 1.5,
        "frozen vegetables" to 2.0, "frozen peas" to 2.0, "frozen corn" to 2.0,
        "frozen broccoli" to 2.5, "mixed vegetables" to 2.0,
        "frozen spinach" to 2.0, "canned corn" to 1.5, "canned green beans" to 1.5,
    )

    private val SAUCES = mapOf(
        "salsa" to 2.5, "pasta sauce" to 2.5, "marinara" to 2.5, "soy sauce" to 2.0,
        "hot sauce" to 2.5, "teriyaki" to 3.0, "bbq sauce" to 2.5,
        "buffalo sauce" to 2.5, "coconut milk" to 2.5, "curry paste" to 3.0,
        "tomato sauce" to 1.5, "tomato paste" to 1.0, "diced tomatoes" to 1.5,
        "canned tomatoes" to 1.5, "ranch" to 2.5, "cream of mushroom" to 2.0,
        "cream of chicken" to 2.0, "broth" to 2.0, "chicken broth" to 2.0,
        "vegetable broth" to 2.0, "taco seasoning" to 1.5, "chili powder" to 2.0,
        "peanut sauce" to 3.0, "alfredo" to 3.0, "pesto" to 3.5,
        "enchilada sauce" to 2.5, "hoisin" to 3.0, "oyster sauce" to 2.5,
        "fish sauce" to 2.5, "gochujang" to 3.0, "worcestershire" to 3.0,
        "balsamic vinegar" to 3.0, "red wine vinegar" to 2.5, "apple cider vinegar" to 2.5,
        "ketchup" to 2.0, "mustard" to 2.0, "honey" to 3.5, "maple syrup" to 4.0,
        "sweet chili sauce" to 3.0, "sriracha" to 3.0, "tahini" to 4.0,
        "hummus" to 3.0, "mole" to 3.5, "harissa" to 3.5, "tzatziki" to 3.0,
    )

    private val DAIRY = setOf(
        "cheddar", "cheese", "mozzarella", "parmesan", "cream", "sour cream",
        "yogurt", "greek yogurt", "milk", "butter", "cream cheese", "feta",
        "ricotta", "cottage cheese", "half and half", "heavy cream", "swiss",
        "provolone", "pepper jack", "gouda", "goat cheese", "queso fresco",
        "blue cheese", "paneer", "american cheese",
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
        "taco seasoning", "curry powder", "bay leaf", "garam masala",
        "cajun seasoning", "old bay", "adobo", "five spice", "jerk seasoning",
        "poultry seasoning", "seasoned salt", "celery salt", "dill", "sage",
        "sumac", "za'atar", "steak seasoning", "italian dressing mix",
        "ranch mix", "taco mix", "chili flakes", "cinnamon", "nutmeg",
        "vanilla extract", "cocoa powder", "maple flavor",
    )

    /** Fresh / dried herbs the user may own — woven into the dish as a finish. */
    private val HERBS = setOf(
        "cilantro", "parsley", "basil", "dill", "chives", "mint", "oregano",
        "thyme", "rosemary", "sage", "tarragon",
    )

    /** Citrus / fresh accents the generator can use as a bright finish. */
    private val CITRUS = setOf(
        "lemon", "lime", "orange", "grapefruit", "pineapple", "mango",
    )

    /** Nuts / seeds / dried fruit accents. */
    private val ACCENTS = setOf(
        "peanuts", "almonds", "walnuts", "cashews", "pecans", "sesame seeds",
        "sunflower seeds", "raisins", "cranberries", "coconut", "chia seeds",
        "flax seeds", "pumpkin seeds", "pine nuts", "hazelnuts",
    )

    /** Words that never identify a role (brands, packaging, quality). */
    private val NOISE = setOf(
        "great value", "good & gather", "kirkland", "signature", "members mark",
        "aldi", "kroger", "organic", "boneless", "skinless", "fresh", "frozen",
        "large", "small", "medium", "premium", "cage free", "free range",
        "store brand", "private label", "big", "family", "pack", "value",
        "plain", "original", "classic", "trademark", "simple", "real",
    )

    private fun norm(s: String): String = s.lowercase(Locale.US).trim()

    /** Split a pantry name into significant tokens (brand/noise words stripped). */
    private fun tokens(name: String): Set<String> {
        val n = norm(name)
        return n.split(Regex("[^a-z0-9&']+"))
            .filter { it.isNotBlank() && it.length > 1 }
            .filterNot { it in NOISE }
            .toSet()
    }

    /** True when the pantry name plausibly covers the ingredient class. */
    private fun covers(pantryName: String, klass: String): Boolean {
        val k = norm(klass)
        if (k in norm(pantryName) || norm(pantryName) in k) return true
        val pTokens = tokens(pantryName)
        val kTokens = tokens(klass)
        if (pTokens.isEmpty() || kTokens.isEmpty()) return false
        // "boneless skinless chicken breast" covers "chicken breast" when the
        // ingredient's tokens are all present among the pantry item's tokens.
        return kTokens.all { it in pTokens }
    }

    /** Match a pantry item to a known ingredient class, longest-key-first. */
    private fun matchItem(name: String, table: Map<String, Double>): String? =
        table.keys
            .filter { covers(name, it) }
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
            val hit = SEASONINGS.firstOrNull { covers(n, it) } ?: continue
            found.add(hit)
        }
        return found.toList()
    }

    /** Herbs actually present in the pantry, in pantry order. */
    private fun pantryHerbs(items: List<PantryItem>): List<String> {
        val found = linkedSetOf<String>()
        for (i in items) {
            val n = norm(i.name)
            val hit = HERBS.firstOrNull { covers(n, it) } ?: continue
            found.add(hit)
        }
        return found.toList()
    }

    /** Citrus/fruit accents actually present in the pantry. */
    private fun pantryCitrus(items: List<PantryItem>): List<String> {
        val found = linkedSetOf<String>()
        for (i in items) {
            val n = norm(i.name)
            val hit = CITRUS.firstOrNull { covers(n, it) } ?: continue
            found.add(hit)
        }
        return found.toList()
    }

    /** Nuts/seeds/dried fruit accents actually present in the pantry. */
    private fun pantryAccents(items: List<PantryItem>): List<String> {
        val found = linkedSetOf<String>()
        for (i in items) {
            val n = norm(i.name)
            val hit = ACCENTS.firstOrNull { covers(n, it) } ?: continue
            found.add(hit)
        }
        return found.toList()
    }

    /** The 12 cooking methods — the backbone of every generated dish. */
    private val METHODS = listOf(
        "skillet", "sheet pan", "one pot", "slow cooker",
        "stir-fry", "casserole", "taco bowl", "soup",
        "grill", "air fryer", "pasta bake", "burrito bowl",
    )

    /**
     * 12 flavor directions. When the pantry has its own sauce (salsa, soy,
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
        Flavor("Buffalo", "buffalo sauce", "cayenne and garlic powder", "blue cheese"),
        Flavor("Honey Garlic", "soy sauce", "honey, garlic, and a splash of vinegar", "sesame seeds"),
        Flavor("Pesto", "pesto", "garlic", "parmesan"),
        Flavor("Cajun", "tomato sauce", "cajun seasoning", "green onion"),
    )

    /**
     * Real food photos (Wikimedia Commons, verified) for generated recipes,
     * keyed by the protein that leads the dish. The on-device card shows the
     * photo that matches the main ingredient — never a random stock image.
     */
    private val PROTEIN_PHOTOS = mapOf(
        "chicken breast" to "https://upload.wikimedia.org/wikipedia/commons/thumb/0/05/2020-08-30_06_01_03_Close_view_of_Healthy_Choice_Cafe_Steamers_Grilled_Chicken_Marinara_with_Parmesan_%28Grilled_Chicken_Breast_with_Penne_Pasta_%26_Broccoli%29_in_the_Dulles_section_of_Sterling%2C_Loudoun_County%2C_Virginia.jpg/500px-thumbnail.jpg",
        "chicken" to "https://upload.wikimedia.org/wikipedia/commons/thumb/b/b1/2020-08-30_06_00_51_A_serving_of_Healthy_Choice_Cafe_Steamers_Grilled_Chicken_Marinara_with_Parmesan_%28Grilled_Chicken_Breast_with_Penne_Pasta_%26_Broccoli%29_in_the_Dulles_section_of_Sterling%2C_Loudoun_County%2C_Virginia.jpg/500px-2020-08-30_06_00_51_A_serving_of_Healthy_Choice_Cafe_Steamers_Grilled_Chicken_Marinara_with_Parmesan_%28Grilled_Chicken_Breast_with_Penne_Pasta_%26_Broccoli%29_in_the_Dulles_section_of_Sterling%2C_Loudoun_County%2C_Virginia.jpg",
        "ground beef" to "https://upload.wikimedia.org/wikipedia/commons/thumb/1/19/2020-08-09_21_39_35_Ground_beef_starting_to_cook_on_a_hot_skillet_in_the_Franklin_Farm_section_of_Oak_Hill%2C_Fairfax_County%2C_Virginia.jpg/500px-2020-08-09_21_39_35_Ground_beef_starting_to_cook_on_a_hot_skillet_in_the_Franklin_Farm_section_of_Oak_Hill%2C_Fairfax_County%2C_Virginia.jpg",
        "beef" to "https://upload.wikimedia.org/wikipedia/commons/thumb/5/5a/Cooking_stir_fry_beef%2C_Terrytown%2C_Louisiana%2C_February_2016.jpg/500px-Cooking_stir_fry_beef%2C_Terrytown%2C_Louisiana%2C_February_2016.jpg",
        "salmon" to "https://upload.wikimedia.org/wikipedia/commons/thumb/d/d0/Liat_Portal_for_Foodie_Disorder_-_Grilled_salmon_with_sweet_potatoes.jpg/500px-Liat_Portal_for_Foodie_Disorder_-_Grilled_salmon_with_sweet_potatoes.jpg",
        "shrimp" to "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a6/Jilibeng_%28chicken_and_shrimp_stir-fry%29.jpg/500px-Jilibeng_%28chicken_and_shrimp_stir-fry%29.jpg",
        "tofu" to "https://upload.wikimedia.org/wikipedia/commons/thumb/7/7a/Black_pepper_tofu_fried_udon_-_Stir_Fry_by_CK.jpg/500px-Black_pepper_tofu_fried_udon_-_Stir_Fry_by_CK.jpg",
        "turkey" to "https://upload.wikimedia.org/wikipedia/commons/thumb/f/f5/Cooked_turkey_breast_-_November_2023_-_Sarah_Stierch.jpg/500px-Cooked_turkey_breast_-_November_2023_-_Sarah_Stierch.jpg",
        "eggs" to "https://upload.wikimedia.org/wikipedia/commons/thumb/7/71/Egg_scramble_in_a_plate.jpg/500px-Egg_scramble_in_a_plate.jpg",
        "lentils" to "https://upload.wikimedia.org/wikipedia/commons/thumb/f/f2/Baati_%E0%A4%AC%E0%A4%BE%E0%A4%9F%E0%A5%80_01.jpg/500px-Baati_%E0%A4%AC%E0%A4%BE%E0%A4%9F%E0%A5%80_01.jpg",
        "black beans" to "https://upload.wikimedia.org/wikipedia/commons/thumb/5/58/2019-05-04_20_10_55_A_burrito_bowl_from_Chipotle_in_the_Franklin_Farm_section_of_Oak_Hill%2C_Fairfax_County%2C_Virginia.jpg/500px-2019-05-04_20_10_55_A_burrito_bowl_from_Chipotle_in_the_Franklin_Farm_section_of_Oak_Hill%2C_Fairfax_County%2C_Virginia.jpg",
        "pork" to "https://upload.wikimedia.org/wikipedia/commons/thumb/6/6e/Chop_suey.jpg/500px-Chop_suey.jpg",
        "sausage" to "https://upload.wikimedia.org/wikipedia/commons/thumb/4/4f/Jambalaya_%2830750226186%29.jpg/500px-Jambalaya_%2830750226186%29.jpg",
    )

    fun photoFor(proteinName: String): String? =
        PROTEIN_PHOTOS.entries.firstOrNull { (k, _) ->
            norm(proteinName).contains(norm(k)) || norm(k).contains(norm(proteinName))
        }?.value

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
        "grill" -> "Grilled Dinner"
        "air fryer" -> "Air-Fryer Dinner"
        "pasta bake" -> "Pasta Bake"
        "burrito bowl" -> "Burrito Bowl"
        else -> "Skillet"
    }

    /**
     * Generate a new dinner recipe from the pantry. The same pantry + variant
     * always yields the same recipe (stable and testable). Variants walk
     * through 144 distinct (method × flavor) blueprints and rotate which of the
     * user's own items leads each role, so "Try another" gives something new
     * for many rolls — never the same dish renamed.
     */
    fun generate(items: List<PantryItem>, focus: String = "balanced", variant: Int = 0): GeneratedRecipe {
        val pantry = items.distinctBy { norm(it.name) }

        // Every matching pantry item per role, in pantry order.
        val proteins = collectMatches(pantry, PROTEINS)
        val starches = collectMatches(pantry, STARCHES)
        val veggies = collectMatches(pantry, VEGGIES)
        val sauces = collectMatches(pantry, SAUCES)
        val dairyNames = pantry.map { it.name }.filter { n -> DAIRY.any { covers(n, it) } }
        val seasonings = pantrySeasonings(pantry)
        val herbs = pantryHerbs(pantry)
        val citrus = pantryCitrus(pantry)
        val accents = pantryAccents(pantry)

        // Blueprint: a deterministic scramble (Knuth multiplicative hash) of
        // the variant, so consecutive "Try another" taps jump across methods,
        // flavors, AND the user's own pantry items at once — the first several
        // rolls are all genuinely different dishes, never the same one renamed.
        val scrambled = Math.floorMod(variant.toLong() * 2654435761L, 1L shl 31).toInt()
        val methodIdx = Math.floorMod(scrambled, METHODS.size)
        val flavorIdx = Math.floorMod(scrambled / METHODS.size, FLAVORS.size)
        val itemRot = Math.floorMod(scrambled / (METHODS.size * FLAVORS.size), 12)
        val nameStyle = Math.floorMod(scrambled / (METHODS.size * FLAVORS.size * 12), 3)
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

        // Dairy + seasonings + herbs + citrus + accents from the pantry when present.
        val dairyName = if (dairyNames.isNotEmpty()) dairyNames[((itemRot % dairyNames.size) + dairyNames.size) % dairyNames.size] else null
        val seasoning = if (seasonings.isNotEmpty()) seasonings[((itemRot % seasonings.size) + seasonings.size) % seasonings.size] else null
        val herb = if (herbs.isNotEmpty()) herbs[((itemRot % herbs.size) + herbs.size) % herbs.size] else null
        val citrusName = if (citrus.isNotEmpty()) citrus[((itemRot % citrus.size) + citrus.size) % citrus.size] else null
        val accent = if (accents.isNotEmpty()) accents[((itemRot % accents.size) + accents.size) % accents.size] else null

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
            herb?.let { add(it) }
            citrusName?.let { add(it) }
            accent?.let { add(it) }
        }

        // Deterministic seed so the same pantry + variant is stable.
        val seedSum = pantry.sumOf { norm(it.name).hashCode().toLong() } + variant.toLong() * 7919L

        // Dish name: flavor + protein + style, with the starch/veg woven in so
        // every blueprint reads as its own dish, not a renamed copy. The
        // flavor direction always shows — even when a pantry sauce leads, the
        // flavor label rides along ("BBQ Salsa Chicken ..."), so two flavors
        // that pick the same pantry sauce never collapse into one name.
        val flavorLead = if (pantrySauce != null) {
            val s = title(pantrySauce.first)
            if (flavor.label.equals(s, ignoreCase = true)) s
            else "${flavor.label} $s"
        } else {
            flavor.label
        }
        val style = methodLabel(method)
        val proteinTitle = title(proteinName)
        val starchTitle = title(starchName)
        val vegTitle = title(vegName)
        val dishName = when (nameStyle) {
            1 -> buildString {
                append(proteinTitle)
                append(" ")
                append(style)
                append(" with ")
                append(starchTitle)
                append(", ")
                append(vegTitle)
                append(" & ")
                append(flavorLead)
                veg2?.let { append(" + "); append(title(it.first)) }
            }
            2 -> buildString {
                append(style)
                append(" ")
                append(flavorLead)
                append(" ")
                append(proteinTitle)
                append(" over ")
                append(starchTitle)
                append(" and ")
                append(vegTitle)
                veg2?.let { append(" + "); append(title(it.first)) }
            }
            else -> buildString {
                append(flavorLead)
                append(" ")
                append(proteinTitle)
                append(" ")
                append(style)
                append(" with ")
                append(starchTitle)
                append(" and ")
                append(vegTitle)
                veg2?.let { append(", "); append(title(it.first)) }
            }
        }

        val totalCost = proteinCost + starchCost + vegCost +
            (veg2?.second ?: 0.0) +
            (sauce?.second ?: 0.0) +
            (if (dairyName != null) 2.5 else 0.0) +
            (if (accent != null) 2.0 else 0.0)
        val servings = 4
        val prep = if (method == "slow cooker" || method == "soup") 10 else 12
        val cook = when (method) {
            "slow cooker" -> 240
            "sheet pan", "casserole", "pasta bake" -> 32
            "stir-fry", "grill", "air fryer" -> 15
            "taco bowl", "burrito bowl" -> 20
            "soup" -> 35
            else -> 18
        }

        val ingredients = buildList {
            add(Ingredient(name = proteinName, amount = "1 lb", brand = "store brand or your favorite"))
            add(Ingredient(name = starchName, amount = if (norm(starchName).contains("rice")) "1 cup dry" else "half a package", brand = "store brand"))
            add(Ingredient(name = vegName, amount = if (norm(vegName).startsWith("frozen") || norm(vegName).startsWith("canned")) "1 bag or can" else "2 cups, chopped", brand = "store brand or fresh"))
            veg2?.let { add(Ingredient(name = it.first, amount = if (norm(it.first).startsWith("frozen") || norm(it.first).startsWith("canned")) "1 bag or can" else "2 cups, chopped", brand = "store brand or fresh")) }
            sauce?.let { add(Ingredient(name = it.first, amount = "1 jar or can", brand = "your favorite brand")) }
            dairyName?.let { add(Ingredient(name = it, amount = "1 cup", brand = "store brand")) }
            seasoning?.let { add(Ingredient(name = it, amount = "1 tsp", brand = null)) }
            herb?.let { add(Ingredient(name = it, amount = "a handful, chopped", brand = null)) }
            citrusName?.let { add(Ingredient(name = it, amount = "1, juiced", brand = null)) }
            accent?.let { add(Ingredient(name = it, amount = "2 tbsp", brand = null)) }
            add(Ingredient(name = "salt", amount = "to taste", brand = null))
            add(Ingredient(name = "black pepper", amount = "to taste", brand = null))
            add(Ingredient(name = "cooking oil", amount = "1 tbsp", brand = null))
        }

        val seasoningUse = seasoning ?: flavor.seasonings
        val finisher = listOfNotNull(dairyName, herb, citrusName?.let { "${title(it)} juice" }, accent, flavor.finisher).joinToString(" and ")
            .ifBlank { flavor.finisher }
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
            "grill" -> listOf(
                "Heat a grill or grill pan to medium-high and oil the grates.",
                "Season the ${proteinName} with ${seasoningUse} and grill 5–6 minutes per side until cooked through.",
                "Brush the ${vegName}${veg2?.let { " and ${it.first}" } ?: ""} with a little oil and grill alongside, 4–5 minutes.",
                "Cook the ${starchName} while the grill works.",
                "Rest the protein 5 minutes, then serve over the ${starchName} with ${sauceWord} and ${finisher}.",
            )
            "air fryer" -> listOf(
                "Heat an air fryer to 400°F.",
                "Toss the ${vegName}${veg2?.let { " and ${it.first}" } ?: ""} with 1 tsp oil, salt, and ${seasoningUse} in the basket.",
                "Air-fry the ${proteinName} for 12–15 minutes, flipping halfway, until cooked through.",
                "Cook the ${starchName} while the basket works.",
                "Toss everything with the ${sauceWord} and serve over the ${starchName}, finished with ${finisher}.",
            )
            "pasta bake" -> listOf(
                "Heat the oven to 375°F and lightly grease a baking dish.",
                "Cook the ${starchName} until just shy of al dente; drain.",
                "Brown the ${proteinName} with ${seasoningUse}, then stir in the ${vegName}${veg2?.let { " and ${it.first}" } ?: ""} and ${sauceWord}.",
                "Combine everything in the dish, top with ${finisher}, and bake 20 minutes until bubbling.",
                "Rest 5 minutes before serving.",
            )
            "burrito bowl" -> listOf(
                "Warm 1 tbsp oil in a skillet over medium heat.",
                "Cook the ${proteinName} with ${seasoningUse} until browned, 5–6 minutes.",
                "Prepare the ${starchName} and warm the ${vegName}${veg2?.let { " and ${it.first}" } ?: ""}.",
                "Build bowls: ${starchName}, ${proteinName}, veggies, ${sauceWord}, and ${finisher}.",
                "Serve with a squeeze of lime or a spoonful of hot sauce.",
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
            imageUrl = photoFor(proteinName),
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
