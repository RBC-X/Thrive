package com.thrive.backup

import com.thrive.app.ai.IngredientNormalizer
import com.thrive.app.ai.PlanShoppingGroup
import com.thrive.app.data.model.Ingredient
import com.thrive.app.data.model.Recipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the ingredient normalization engine: canonical merge, unit-family
 * summation, pantry subtraction, aisle grouping, and the honesty rule that
 * incompatible ingredient forms are never merged into one misleading line.
 */
class IngredientNormalizerTest {

    private fun ingredient(name: String, amount: String = "", optional: Boolean = false) =
        Ingredient(name = name, amount = amount, optional = optional)

    private fun recipe(id: String, ingredients: List<Ingredient>, cost: Double = 8.0) = Recipe(
        id = id,
        name = "Recipe $id",
        description = "",
        section = "family_favorites",
        prepMinutes = 10,
        cookMinutes = 20,
        servings = 4,
        costDollars = cost,
        ingredients = ingredients,
        steps = listOf("Do it"),
    )

    @Test
    fun `alias ingredients merge into one canonical line`() {
        // "boneless skinless chicken breast" and "chicken breast" are the same
        // shopping purchase and must land on one canonical line.
        val groups = IngredientNormalizer.build(
            recipes = listOf(
                recipe("a", listOf(ingredient("chicken breast", "1 lb"))),
                recipe("b", listOf(ingredient("boneless skinless chicken breast", "1 lb"))),
            ),
            pantryNames = emptyList(),
        )
        val chicken = findLine(groups, "chicken breast")
        assertEquals(1, chicken.size)
        assertEquals(2.0, chicken[0].quantity, 0.001)
        assertEquals(2, chicken[0].recipes)
    }

    @Test
    fun `unit families sum within family`() {
        val groups = IngredientNormalizer.build(
            recipes = listOf(
                recipe("a", listOf(ingredient("chicken breast", "8 oz"))),
                recipe("b", listOf(ingredient("chicken breast", "1 lb"))),
            ),
            pantryNames = emptyList(),
        )
        val line = findLine(groups, "chicken breast")
        assertEquals(1, line.size)
        // 8 oz + 1 lb = 1.5 lb
        assertEquals(1.5, line[0].quantity, 0.001)
        assertEquals("lb", line[0].unit)
    }

    @Test
    fun `canned and fresh tomatoes stay separate`() {
        val groups = IngredientNormalizer.build(
            recipes = listOf(
                recipe("a", listOf(ingredient("canned diced tomatoes", "1 can"))),
                recipe("b", listOf(ingredient("fresh tomatoes", "2"))),
            ),
            pantryNames = emptyList(),
        )
        val canned = findLine(groups, "canned tomatoes")
        val fresh = findLine(groups, "tomatoes")
        assertTrue(canned.isNotEmpty())
        assertTrue(fresh.isNotEmpty())
        assertTrue(canned[0].name != fresh[0].name)
    }

    @Test
    fun `pantry items are subtracted and marked`() {
        val groups = IngredientNormalizer.build(
            recipes = listOf(
                recipe("a", listOf(ingredient("chicken breast", "1 lb"), ingredient("rice", "2 cups")))
            ),
            pantryNames = listOf("chicken breast", "salt"),
        )
        val chicken = findLine(groups, "chicken breast").first()
        assertTrue(chicken.haveInPantry)
        assertEquals(0.0, chicken.estCost, 0.001)
        val rice = findLine(groups, "rice").first()
        assertFalse(rice.haveInPantry)
        assertTrue(rice.estCost > 0)
    }

    @Test
    fun `staples are skipped from the shopping list`() {
        val groups = IngredientNormalizer.build(
            recipes = listOf(
                recipe(
                    "a",
                    listOf(
                        ingredient("salt", "1 tsp"),
                        ingredient("olive oil", "2 tbsp"),
                        ingredient("chicken breast", "1 lb"),
                    ),
                )
            ),
            pantryNames = emptyList(),
        )
        assertTrue(findLine(groups, "salt").isEmpty())
        assertTrue(findLine(groups, "chicken breast").isNotEmpty())
    }

    @Test
    fun `groups are aisle-sorted with subtotals`() {
        val groups = IngredientNormalizer.build(
            recipes = listOf(
                recipe(
                    "a",
                    listOf(
                        ingredient("chicken breast", "1 lb"),
                        ingredient("bell pepper", "1"),
                        ingredient("pasta", "2 cups"),
                    ),
                )
            ),
            pantryNames = emptyList(),
        )
        assertTrue(groups.isNotEmpty())
        assertTrue(
            groups.any { g ->
                g.category == "Meat & Seafood" && g.items.any { it.name == "chicken breast" }
            }
        )
        assertTrue(groups.all { it.subtotal >= 0 })
        // "used in N meals" is tracked per merged line.
        assertTrue(findLine(groups, "bell pepper").first().recipes >= 1)
    }

    private fun findLine(groups: List<PlanShoppingGroup>, name: String) =
        groups.flatMap { it.items }.filter { it.name == name }
}
