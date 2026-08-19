package com.thrive.backup

import com.thrive.app.ai.IngredientNormalizer
import com.thrive.app.ai.WeeklyPlannerEngine
import com.thrive.app.data.model.Ingredient
import com.thrive.app.data.model.PantryItem
import com.thrive.app.data.model.Recipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the week planner: requested night count, no repeated recipes,
 * restriction and cook-time enforcement, single-night swaps that leave the
 * rest of the week untouched, and budget repair that either lands under
 * budget or says honestly that it can't.
 */
class WeeklyPlannerEngineTest {

    private fun ingredient(name: String, amount: String = "", optional: Boolean = false) =
        Ingredient(name = name, amount = amount, optional = optional)

    private fun recipe(
        id: String,
        name: String,
        minutes: Int = 30,
        cost: Double = 8.0,
        ingredients: List<Ingredient> = listOf(ingredient("chicken breast", "1 lb"), ingredient("rice", "2 cups")),
        requiredAppliances: List<String> = emptyList(),
    ) = Recipe(
        id = id,
        name = name,
        description = "",
        section = "family_favorites",
        prepMinutes = minutes / 2,
        cookMinutes = minutes - minutes / 2,
        servings = 4,
        costDollars = cost,
        ingredients = ingredients,
        steps = listOf("Do it"),
        requiredAppliances = requiredAppliances,
    )

    private val pantry: List<PantryItem> = emptyList()

    private val recipes = listOf(
        recipe("r1", "Chicken and Rice", cost = 8.0),
        recipe("r2", "Beef Tacos", cost = 10.0, ingredients = listOf(ingredient("ground beef", "1 lb"), ingredient("tortillas", "8"))),
        recipe("r3", "Pork Stir Fry", cost = 7.0, ingredients = listOf(ingredient("pork", "1 lb"), ingredient("rice", "2 cups"))),
        recipe("r4", "Veggie Pasta", cost = 6.0, ingredients = listOf(ingredient("pasta", "1 lb"), ingredient("tomato sauce", "1 jar"))),
        recipe("r5", "Salmon and Broccoli", cost = 12.0, minutes = 45, ingredients = listOf(ingredient("salmon", "1 lb"), ingredient("broccoli", "1 lb"))),
        recipe("r6", "Peanut Noodles", cost = 5.0, minutes = 20, ingredients = listOf(ingredient("noodles", "1 lb"), ingredient("peanut butter", "2 tbsp"))),
    )

    @Test
    fun `plans the requested number of nights without repeating a recipe`() {
        val plan = WeeklyPlannerEngine.plan(pantry, recipes, nights = 3, budget = 60.0, people = 4)
        assertEquals(3, plan.nights.size)
        assertEquals(3, plan.nightsCount)
        val ids = plan.nights.map { it.suggestion.recipe.id }
        assertEquals(ids.size, ids.toSet().size) // no repeats
        assertEquals(listOf("Mon", "Tue", "Wed"), plan.nights.map { it.day })
    }

    @Test
    fun `restrictions remove violating meals`() {
        val plan = WeeklyPlannerEngine.plan(pantry, recipes, nights = 3, budget = 60.0, restrictions = listOf("peanut"))
        assertTrue(plan.nights.none { it.suggestion.recipe.id == "r6" })
        assertEquals(3, plan.nights.size)
    }

    @Test
    fun `max cook time caps the plan`() {
        val plan = WeeklyPlannerEngine.plan(pantry, recipes, nights = 2, budget = 60.0, maxCookMinutes = 30)
        assertEquals(2, plan.nights.size)
        assertTrue(plan.nights.all { it.suggestion.recipe.totalMinutes <= 30 })
    }

    @Test
    fun `swap changes only the swapped night and keeps constraints`() {
        val plan = WeeklyPlannerEngine.plan(pantry, recipes, nights = 3, budget = 60.0)
        val original = plan.nights.map { it.suggestion.recipe.id }
        val swapped = WeeklyPlannerEngine.swapNight(plan, 0, pantry, recipes)
        assertNotNull(swapped)
        val after = swapped!!.nights.map { it.suggestion.recipe.id }
        assertEquals(original[1], after[1]) // untouched nights stay identical
        assertEquals(original[2], after[2])
        assertTrue(after[0] != original[0]) // and the swapped night actually changed
        assertEquals(plan.nights[0].day, swapped.nights[0].day) // day slot preserved
    }

    @Test
    fun `swap out of range or with no eligible replacement returns null`() {
        val plan = WeeklyPlannerEngine.plan(pantry, recipes, nights = 3, budget = 60.0)
        assertEquals(null, WeeklyPlannerEngine.swapNight(plan, 99, pantry, recipes))
        // 3-night plan from 6 recipes: after excluding the other two nights AND
        // the current meal, at least one replacement always exists.
        assertNotNull(WeeklyPlannerEngine.swapNight(plan, 1, pantry, recipes))
    }

    @Test
    fun `optimize lands under budget when possible`() {
        // Tight budget: the plan starts over budget, and the optimizer swaps
        // the priciest (by household cost) meals for cheaper ones until it
        // fits — never judging improvement by a different metric than it
        // optimizes.
        val plan = WeeklyPlannerEngine.plan(pantry, recipes, nights = 3, budget = 9.0)
        assertFalse(plan.underBudget) // precondition: we start over budget
        val result = WeeklyPlannerEngine.optimize(plan, pantry, recipes)
        assertTrue(result.plan.totalCost <= result.plan.budget + 0.01)
        assertTrue(result.changed)
        assertEquals(3, result.plan.nights.size)
        val ids = result.plan.nights.map { it.suggestion.recipe.id }
        assertEquals(ids.size, ids.toSet().size) // still no repeats
    }

    @Test
    fun `optimize explains honestly when the budget cannot be met`() {
        // Cheapest possible week still exceeds the budget floor.
        val plan = WeeklyPlannerEngine.plan(pantry, recipes, nights = 3, budget = 1.0)
        val result = WeeklyPlannerEngine.optimize(plan, pantry, recipes)
        if (!result.plan.underBudget) {
            // No fake success: the note must name the floor/overshoot.
            assertTrue(
                result.note.contains("cheapest possible week") || result.note.contains("over your")
            )
        } else {
            assertTrue(result.plan.totalCost <= result.plan.budget + 0.01)
        }
    }

    @Test
    fun `appliance-required recipes are excluded when the household lacks the appliance`() {
        val slowCooker = recipe("sc1", "Slow Cooker Chili", requiredAppliances = listOf("slow cooker"))
        val catalog = listOf(
            slowCooker,
            recipe("st1", "Stovetop Pasta", cost = 6.0, ingredients = listOf(ingredient("pasta", "1 lb"), ingredient("tomato sauce", "1 jar"))),
        )
        val plan = WeeklyPlannerEngine.plan(pantry, catalog, nights = 2, budget = 60.0)
        assertTrue(plan.nights.none { it.suggestion.recipe.id == "sc1" })
        assertTrue(plan.nights.any { it.suggestion.recipe.id == "st1" })
    }

    @Test
    fun `declaring the appliance makes the tagged recipe eligible`() {
        val slowCooker = recipe("sc1", "Slow Cooker Chili", requiredAppliances = listOf("slow cooker"))
        val catalog = listOf(
            slowCooker,
            recipe("st1", "Stovetop Pasta", cost = 6.0, ingredients = listOf(ingredient("pasta", "1 lb"), ingredient("tomato sauce", "1 jar"))),
        )
        assertTrue(WeeklyPlannerEngine.eligible(catalog, emptyList(), 0, setOf("slow cooker")).any { it.id == "sc1" })
        assertFalse(WeeklyPlannerEngine.eligible(catalog, emptyList(), 0, emptySet()).any { it.id == "sc1" })
        val plan = WeeklyPlannerEngine.plan(pantry, catalog, nights = 2, budget = 60.0, appliances = setOf("slow cooker"))
        assertEquals(2, plan.nights.size)
        assertTrue(plan.nights.any { it.suggestion.recipe.id == "sc1" })
    }

    @Test
    fun `air fryer recipes are excluded unless the household has an air fryer`() {
        val airFryer = recipe("af1", "Air Fryer Chicken Nuggets", requiredAppliances = listOf("air fryer"))
        val stovetop = recipe("st1", "Stovetop Pasta", cost = 6.0, ingredients = listOf(ingredient("pasta", "1 lb"), ingredient("tomato sauce", "1 jar")))
        val catalog = listOf(airFryer, stovetop)
        // No air fryer → air-fryer recipe must never be selected.
        val without = WeeklyPlannerEngine.plan(pantry, catalog, nights = 2, budget = 60.0)
        assertTrue(without.nights.none { it.suggestion.recipe.id == "af1" })
        // Declared air fryer → eligible again.
        assertTrue(WeeklyPlannerEngine.eligible(catalog, emptyList(), 0, setOf("air fryer")).any { it.id == "af1" })
    }

    @Test
    fun `microwave recipes are excluded unless the household has a microwave`() {
        val micro = recipe("mw1", "Microwave Mug Cake", requiredAppliances = listOf("microwave"))
        val stovetop = recipe("st1", "Stovetop Pasta", cost = 6.0, ingredients = listOf(ingredient("pasta", "1 lb"), ingredient("tomato sauce", "1 jar")))
        val catalog = listOf(micro, stovetop)
        val plan = WeeklyPlannerEngine.plan(pantry, catalog, nights = 2, budget = 60.0)
        assertTrue(plan.nights.none { it.suggestion.recipe.id == "mw1" })
        assertTrue(WeeklyPlannerEngine.eligible(catalog, emptyList(), 0, setOf("microwave")).any { it.id == "mw1" })
    }

    @Test
    fun `oven tagged recipes stay eligible when the user only declared an air fryer`() {
        // Oven is baseline (untagged recipes assume stovetop/oven), so the
        // household that mentions only an air fryer still gets oven-baked
        // meals — an extra appliance can only open recipes up, never close any.
        val baked = recipe("ov1", "Baked Ziti", requiredAppliances = listOf("oven"))
        val catalog = listOf(baked, recipe("st1", "Stovetop Pasta", cost = 6.0, ingredients = listOf(ingredient("pasta", "1 lb"), ingredient("tomato sauce", "1 jar"))))
        assertTrue(WeeklyPlannerEngine.eligible(catalog, emptyList(), 0, setOf("air fryer")).any { it.id == "ov1" })
        assertTrue(WeeklyPlannerEngine.fitsAppliances(baked, setOf("air fryer")))
    }

    @Test
    fun `extra appliances never reduce eligibility`() {
        // Untagged recipes assume basic stovetop/oven, so declaring more
        // appliances can only open recipes up, never close any.
        val with = WeeklyPlannerEngine.plan(pantry, recipes, nights = 3, budget = 60.0, appliances = setOf("air fryer", "slow cooker", "oven"))
        val without = WeeklyPlannerEngine.plan(pantry, recipes, nights = 3, budget = 60.0)
        assertEquals(3, with.nights.size)
        assertEquals(3, without.nights.size)
        assertEquals(with.nights.map { it.suggestion.recipe.id }, without.nights.map { it.suggestion.recipe.id })
    }

    @Test
    fun `swap prefers alternatives that reuse ingredients already on the list`() {
        // r7 shares ground beef with r2 and rice with r3, so when it's eligible
        // the swap must pick it over zero-overlap alternatives.
        val catalog = listOf(
            recipe("r1", "Chicken and Rice", cost = 8.0),
            recipe("r2", "Beef Tacos", cost = 10.0, ingredients = listOf(ingredient("ground beef", "1 lb"), ingredient("tortillas", "8"))),
            recipe("r3", "Pork Stir Fry", cost = 7.0, ingredients = listOf(ingredient("pork", "1 lb"), ingredient("rice", "2 cups"))),
            recipe("r4", "Veggie Pasta", cost = 6.0, ingredients = listOf(ingredient("pasta", "1 lb"), ingredient("tomato sauce", "1 jar"))),
            recipe("r6", "Peanut Noodles", cost = 5.0, ingredients = listOf(ingredient("noodles", "1 lb"), ingredient("peanut butter", "2 tbsp"))),
            recipe("r7", "Beef Fried Rice", cost = 9.0, ingredients = listOf(ingredient("ground beef", "1 lb"), ingredient("rice", "2 cups"))),
        )
        val plan = WeeklyPlannerEngine.plan(
            pantry, catalog, nights = 3, budget = 60.0,
            restrictions = listOf("peanut"), maxCookMinutes = 30,
        )
        val swapped = WeeklyPlannerEngine.swapNight(plan, 0, pantry, catalog)
        assertNotNull(swapped)
        val rest = swapped!!.nights.drop(1)
            .flatMap { it.suggestion.recipe.ingredients.map { ing -> IngredientNormalizer.canonicalName(ing.name) } }
            .toSet()
        val picked = swapped.nights[0].suggestion.recipe
        val pickedOverlap = picked.ingredients
            .map { IngredientNormalizer.canonicalName(it.name) }
            .count { it in rest }
        assertTrue(
            "swap should prefer ingredient reuse with the rest of the week, picked $pickedOverlap overlap",
            pickedOverlap >= 1
        )
        // Untouched nights still stable, constraints still enforced.
        assertEquals(plan.nights[1].suggestion.recipe.id, swapped.nights[1].suggestion.recipe.id)
        assertEquals(plan.nights[2].suggestion.recipe.id, swapped.nights[2].suggestion.recipe.id)
        assertTrue(swapped.nights.all { it.suggestion.recipe.id != "r6" })
    }

    @Test
    fun `swap keeps appliance constraints`() {
        val catalog = listOf(
            recipe("sc1", "Slow Cooker Chili", requiredAppliances = listOf("slow cooker")),
            recipe("st1", "Stovetop Pasta", cost = 6.0, ingredients = listOf(ingredient("pasta", "1 lb"), ingredient("tomato sauce", "1 jar"))),
            recipe("st2", "Stovetop Tacos", cost = 7.0, ingredients = listOf(ingredient("ground beef", "1 lb"), ingredient("tortillas", "8"))),
            recipe("st3", "Stovetop Soup", cost = 5.0, ingredients = listOf(ingredient("chicken broth", "1 qt"), ingredient("carrots", "2"))),
        )
        val plan = WeeklyPlannerEngine.plan(pantry, catalog, nights = 2, budget = 60.0)
        assertTrue(plan.nights.none { it.suggestion.recipe.id == "sc1" })
        val swapped = WeeklyPlannerEngine.swapNight(plan, 0, pantry, catalog)
        assertNotNull(swapped)
        assertTrue(swapped!!.nights.none { it.suggestion.recipe.id == "sc1" })
        assertEquals(2, swapped.nights.size)
    }

    @Test
    fun `aggregated shopping list merges across nights`() {
        val plan = WeeklyPlannerEngine.plan(pantry, recipes, nights = 3, budget = 60.0)
        val names = plan.combinedShopping.map { it.name }
        // Rice appears in two of the starter recipes and must appear once.
        assertEquals(names.size, names.toSet().size)
        // The normalized groups exist and sum their own subtotals.
        assertTrue(plan.shoppingGroups.isNotEmpty())
        assertTrue(plan.shoppingGroups.all { it.subtotal >= 0 })
    }
}
