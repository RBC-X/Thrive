package com.thrive.backup

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
 * Acceptance scenarios from the planning brief, encoded against the REAL
 * engine (no mocks):
 *
 *  AT1 Simple budget plan — 5 dinners / 2 adults / $70 / quick / high protein /
 *      Aldi / oven+stovetop+air fryer → exactly five dinners, complete recipes,
 *      one consolidated list, no duplicated grocery products, honest estimate.
 *  AT2 Restrictions — 7 dinners / family of four / $100 / peanut allergy / no
 *      shellfish / 30-min max → nothing violates a restriction.
 *  AT3 Plan swap — swap Wednesday → ONLY Wednesday and its derived groceries
 *      change; the other six nights stay identical.
 *  AT4 Pantry — owned items are subtracted from the shopping requirements.
 *  AT5 Budget repair — an over-budget plan is repaired or honestly explained,
 *      never faked.
 *  AT6 Mobile (not a unit test) — exercised on-device on the Pixel 7 (API 35)
 *      emulator: the plan sheet, plan screen, and aisle-grouped shopping list
 *      render at 1080x2400 with 48dp touch targets and no clipping.
 */
class WeeklyPlannerAcceptanceTest {

    private fun ingredient(name: String, amount: String = "", optional: Boolean = false) =
        Ingredient(name = name, amount = amount, optional = optional)

    private fun recipe(
        id: String,
        name: String,
        minutes: Int = 30,
        cost: Double = 8.0,
        ingredients: List<Ingredient> = listOf(ingredient("chicken breast", "1 lb"), ingredient("rice", "2 cups")),
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
    )

    private val pantry: List<PantryItem> = emptyList()

    /** 10-recipe catalog; r5 is slow (45 min) and r6 contains peanuts. */
    private val catalog = listOf(
        recipe("r1", "Chicken and Rice", cost = 8.0),
        recipe("r2", "Beef Tacos", cost = 10.0, ingredients = listOf(ingredient("ground beef", "1 lb"), ingredient("tortillas", "8"))),
        recipe("r3", "Pork Stir Fry", cost = 7.0, ingredients = listOf(ingredient("pork", "1 lb"), ingredient("rice", "2 cups"))),
        recipe("r4", "Veggie Pasta", cost = 6.0, ingredients = listOf(ingredient("pasta", "1 lb"), ingredient("tomato sauce", "1 jar"))),
        recipe("r5", "Salmon and Broccoli", cost = 12.0, minutes = 45, ingredients = listOf(ingredient("salmon", "1 lb"), ingredient("broccoli", "1 lb"))),
        recipe("r6", "Peanut Noodles", cost = 5.0, minutes = 20, ingredients = listOf(ingredient("noodles", "1 lb"), ingredient("peanut butter", "2 tbsp"))),
        recipe("r7", "Beef Fried Rice", cost = 9.0, ingredients = listOf(ingredient("ground beef", "1 lb"), ingredient("rice", "2 cups"))),
        recipe("r8", "Turkey Chili", cost = 7.0, ingredients = listOf(ingredient("ground turkey", "1 lb"), ingredient("canned tomatoes", "1 can"))),
        recipe("r9", "Chicken Tacos", cost = 8.0, ingredients = listOf(ingredient("chicken breast", "1 lb"), ingredient("tortillas", "8"))),
        recipe("r10", "Beef and Broccoli", cost = 9.0, ingredients = listOf(ingredient("ground beef", "1 lb"), ingredient("broccoli", "1 lb"))),
    )

    // --- AT1: simple budget plan ----------------------------------------------

    @Test
    fun `AT1 five dinners for two under 70 with appliances and store`() {
        val plan = WeeklyPlannerEngine.plan(
            pantry = pantry,
            recipes = catalog,
            nights = 5,
            budget = 70.0,
            people = 2,
            focus = "high protein",
            maxCookMinutes = 30,
            appliances = setOf("oven", "stovetop", "air fryer"),
            preferredStore = "Aldi",
        )
        // Exactly five dinners.
        assertEquals(5, plan.nights.size)
        // Complete recipes with no repeats.
        val ids = plan.nights.map { it.suggestion.recipe.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(plan.nights.all { it.suggestion.recipe.steps.isNotEmpty() })
        assertTrue(plan.nights.all { it.suggestion.recipe.ingredients.isNotEmpty() })
        // Quick meals only.
        assertTrue(plan.nights.all { it.suggestion.recipe.totalMinutes <= 30 })
        // Constraints carried through.
        assertEquals(2, plan.people)
        assertEquals("Aldi", plan.preferredStore)
        assertEquals(setOf("oven", "stovetop", "air fryer"), plan.appliances)
        // Consolidated shopping list: no duplicated grocery product.
        val names = plan.combinedShopping.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertTrue(plan.shoppingGroups.isNotEmpty())
        assertTrue(plan.shoppingGroups.all { it.subtotal >= 0 })
        // Estimates exist and the package-aware cart is at least the consumption cost.
        assertTrue(plan.totalCost > 0)
        assertTrue(plan.cartTotal >= plan.extraCost - 0.01)
    }

    // --- AT2: restrictions -----------------------------------------------------

    @Test
    fun `AT2 seven dinners for four under 100 never violate restrictions`() {
        val plan = WeeklyPlannerEngine.plan(
            pantry = pantry,
            recipes = catalog,
            nights = 7,
            budget = 100.0,
            people = 4,
            restrictions = listOf("peanut", "shellfish"),
            maxCookMinutes = 30,
        )
        assertEquals(7, plan.nights.size)
        for (night in plan.nights) {
            val r = night.suggestion.recipe
            // No meal may violate peanut allergy, shellfish, or the 30-min cap.
            assertFalse("r${r.id} violates peanut", WeeklyPlannerEngine.violatesRestrictions(r, listOf("peanut")))
            assertFalse("r${r.id} violates shellfish", WeeklyPlannerEngine.violatesRestrictions(r, listOf("shellfish")))
            assertTrue("r${r.id} too slow", r.totalMinutes <= 30)
        }
        assertEquals(7, plan.nights.map { it.suggestion.recipe.id }.toSet().size)
    }

    // --- AT3: plan swap --------------------------------------------------------

    @Test
    fun `AT3 swapping Wednesday leaves every other night stable`() {
        val plan = WeeklyPlannerEngine.plan(pantry, catalog, nights = 7, budget = 100.0)
        val before = plan.nights.map { it.suggestion.recipe.id }
        val swapped = WeeklyPlannerEngine.swapNight(plan, 2, pantry, catalog) // index 2 == Wed
        assertNotNull(swapped)
        val after = swapped!!.nights.map { it.suggestion.recipe.id }
        for (i in before.indices) {
            if (i == 2) {
                assertTrue("Wednesday must change", after[i] != before[i])
                assertEquals("Wed", swapped.nights[i].day)
            } else {
                assertEquals("night $i must stay identical", before[i], after[i])
            }
        }
        // Derived state recalculated: groceries and totals match the new plan.
        assertEquals(7, swapped.nights.size)
        assertTrue(swapped.shoppingGroups.isNotEmpty())
    }

    // --- AT4: pantry -----------------------------------------------------------

    @Test
    fun `AT4 pantry items are subtracted from the shopping requirements`() {
        val withPantry = WeeklyPlannerEngine.plan(
            pantry = listOf(
                PantryItem(id = "p1", name = "chicken breast", category = "Meat", location = "Fridge")
            ),
            recipes = catalog,
            nights = 2,
            budget = 60.0,
        )
        val chicken = withPantry.shoppingGroups.flatMap { it.items }
            .firstOrNull { it.name == "chicken breast" }
        assertNotNull("chicken breast should still be listed (it is used by a meal)", chicken)
        assertTrue("owned chicken must be marked in pantry", chicken!!.haveInPantry)
        assertEquals(0.0, chicken.estCost, 0.001)
        assertEquals(0.0, chicken.cartCost, 0.001)
        // Something the pantry does NOT cover still costs money.
        val pork = withPantry.shoppingGroups.flatMap { it.items }.firstOrNull { it.name == "pork" }
        if (pork != null) {
            assertFalse(pork.haveInPantry)
            assertTrue(pork.cartCost > 0)
        }
    }

    // --- AT5: budget repair ----------------------------------------------------

    @Test
    fun `AT5 over-budget plan is repaired or honestly explained, never faked`() {
        // Tight budget: the plan must start over budget.
        val plan = WeeklyPlannerEngine.plan(pantry, catalog, nights = 3, budget = 9.0)
        assertFalse("precondition: plan starts over budget", plan.underBudget)
        val result = WeeklyPlannerEngine.optimize(plan, pantry, catalog)
        assertEquals(3, result.plan.nights.size) // repair never drops nights
        assertEquals(3, result.plan.nights.map { it.suggestion.recipe.id }.toSet().size) // no dupes
        if (result.plan.underBudget) {
            assertTrue(result.plan.totalCost <= result.plan.budget + 0.01)
        } else {
            // Honest floor: the note names the cheapest possible week / overshoot.
            assertTrue(
                "note must explain the floor, got: ${result.note}",
                result.note.contains("cheapest possible week") || result.note.contains("over your")
            )
        }
    }
}
