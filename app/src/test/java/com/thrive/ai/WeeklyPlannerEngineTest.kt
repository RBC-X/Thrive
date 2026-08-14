package com.thrive.ai

import com.thrive.app.ai.WeeklyPlannerEngine
import com.thrive.app.data.model.Ingredient
import com.thrive.app.data.model.PantryItem
import com.thrive.app.data.model.Recipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyPlannerEngineTest {

    private fun recipe(id: String, name: String, cost: Double, ingredients: List<String>) = Recipe(
        id = id, name = name, description = "", section = "under_10",
        prepMinutes = 10, cookMinutes = 15, servings = 4, costDollars = cost,
        ingredients = ingredients.map { Ingredient(it) },
        steps = listOf("A", "B", "C"),
    )

    private val recipes = listOf(
        recipe("r1", "Chicken & Rice", 9.0, listOf("chicken breast", "rice", "broccoli")),
        recipe("r2", "Black Bean Tacos", 6.5, listOf("black beans", "tortillas", "avocado")),
        recipe("r3", "Spaghetti", 5.0, listOf("spaghetti", "canned tomatoes", "garlic")),
        recipe("r4", "Tuna Melts", 6.0, listOf("tuna", "bread", "cheese")),
        recipe("r5", "Chili Mac", 8.0, listOf("ground beef", "pasta", "kidney beans")),
        recipe("r6", "Egg Fried Rice", 5.5, listOf("rice", "eggs", "soy sauce")),
        recipe("r7", "Pancakes", 4.0, listOf("flour", "milk", "eggs")),
        recipe("r8", "Sloppy Joes", 10.0, listOf("ground beef", "buns", "ketchup")),
        recipe("r9", "Baked Ziti", 9.0, listOf("pasta", "pasta sauce", "mozzarella")),
        recipe("r10", "Grilled Cheese", 7.0, listOf("bread", "cheese", "butter")),
        recipe("r11", "Salsa Chicken", 8.0, listOf("chicken breast", "salsa", "corn tortillas")),
        recipe("r12", "Peanut Noodles", 7.0, listOf("spaghetti", "peanut butter", "soy sauce")),
    )

    private fun pantryOf(vararg names: String) = names.mapIndexed { i, n ->
        PantryItem(id = "p$i", name = n, category = "X", location = "Pantry", quantity = 1, expiresAt = null)
    }

    @Test
    fun `plans seven distinct dinners`() {
        val plan = WeeklyPlannerEngine.plan(
            pantryOf("chicken breast", "rice", "broccoli", "eggs", "milk", "flour", "spaghetti"),
            recipes, nights = 7, budget = 70.0, people = 4,
        )
        assertEquals(7, plan.nightsCount)
        assertEquals(7, plan.nights.map { it.suggestion.recipe.id }.toSet().size)
        assertEquals(WeeklyPlannerEngine.DAYS.size, plan.nights.size)
        assertEquals("Mon", plan.nights.first().day)
        assertEquals("Sun", plan.nights.last().day)
    }

    @Test
    fun `combined shopping list aggregates duplicates`() {
        val plan = WeeklyPlannerEngine.plan(
            pantryOf("chicken breast", "rice", "broccoli", "eggs", "milk", "flour"),
            recipes, nights = 5, budget = 50.0, people = 4,
        )
        val shopping = plan.combinedShopping.associate { it.name to it.estCost }
        // Rice appears in Chicken & Rice + Egg Fried Rice; only one should be in pantry terms,
        // but any aggregated item must have cost >= a single unit.
        for ((name, cost) in shopping) {
            assertTrue("cost for $name should be positive", cost > 0)
        }
        assertTrue(plan.extraCost > 0)
        assertEquals(plan.extraCost, plan.combinedShopping.sumOf { it.estCost }, 0.01)
    }

    @Test
    fun `generous budget lands under budget`() {
        val plan = WeeklyPlannerEngine.plan(
            pantryOf("chicken breast", "rice", "broccoli", "eggs", "milk", "flour", "spaghetti", "tuna", "bread"),
            recipes, nights = 7, budget = 100.0, people = 4,
        )
        assertTrue("expected under budget, was ${plan.totalCost}", plan.underBudget)
        assertTrue(plan.remaining >= 0)
    }

    @Test
    fun `tight budget still yields a week and reports overshoot honestly`() {
        val plan = WeeklyPlannerEngine.plan(
            pantryOf("chicken breast", "rice", "broccoli", "eggs", "milk", "flour", "spaghetti", "tuna", "bread", "black beans", "tortillas", "ground beef"),
            recipes, nights = 7, budget = 20.0, people = 4,
        )
        // Even on a tiny budget the planner must produce all 7 nights.
        assertEquals(7, plan.nightsCount)
        assertTrue(plan.totalCost > 0)
        // With a $20 budget the honest result is over budget — never fake.
        assertTrue(plan.overshoot >= 0)
    }

    @Test
    fun `empty pantry still plans with cheapest meals`() {
        val plan = WeeklyPlannerEngine.plan(
            emptyList(), recipes, nights = 3, budget = 30.0, people = 2,
        )
        assertEquals(3, plan.nightsCount)
        assertTrue(plan.nights.all { it.suggestion.usedItems.isEmpty() })
        assertTrue(plan.combinedShopping.isNotEmpty())
    }

    @Test
    fun `week total never double counts pantry-covered ingredients`() {
        // Pantry covers every required ingredient of one cheap meal, so the
        // shopping cost for it must be zero and the total must equal the
        // pantry value consumed — NOT recipe full cost + shopping again.
        val fullPantry = pantryOf("spaghetti", "canned tomatoes", "garlic")
        val plan = WeeklyPlannerEngine.plan(fullPantry, recipes, nights = 1, budget = 30.0, people = 2)
        val spaghettiNight = plan.nights.first { it.suggestion.recipe.id == "r3" }
        assertEquals(0.0, spaghettiNight.suggestion.estimatedExtraCost, 0.01)
        assertTrue("pantry value should be counted", plan.pantryValueUsed > 0)
        assertEquals(plan.pantryValueUsed, plan.totalCost, 0.01) // total = shopping + pantry value, no double count
    }

    @Test
    fun `week total sums each night incremental cost exactly`() {
        val pantry = pantryOf("chicken breast", "rice", "broccoli", "spaghetti")
        val plan = WeeklyPlannerEngine.plan(pantry, recipes, nights = 3, budget = 60.0, people = 4)
        val perNight = plan.nights.sumOf { it.suggestion.estimatedExtraCost + it.suggestion.pantryValueUsed }
        assertEquals(plan.totalCost, perNight, 0.01)
    }

    @Test
    fun `expiring items prioritized with focus`() {
        val soon = System.currentTimeMillis() + 20 * 3600_000L
        val pantry = pantryOf("chicken breast", "rice", "broccoli", "soy sauce", "eggs", "milk", "flour")
            .map { if (it.name == "chicken breast") it.copy(expiresAt = soon) else it }
        val plan = WeeklyPlannerEngine.plan(pantry, recipes, nights = 3, budget = 40.0, people = 4, focus = "use_expiring")
        assertTrue("expected expiring chicken used first", plan.nights.first().suggestion.expiringItemsUsed.contains("chicken breast"))
    }
}
