package com.thrive.backup

import com.thrive.app.ai.RecipeMakerEngine
import com.thrive.app.data.model.PantryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeMakerEngineTest {

    private fun item(name: String) = PantryItem(
        id = name, name = name, category = "Grocery", location = "Pantry",
    )

    @Test
    fun `full pantry yields a complete recipe with steps and ingredients`() {
        val gen = RecipeMakerEngine.generate(listOf(
            item("Chicken breast"), item("Rice"), item("Broccoli"), item("Salsa"),
        ))
        val r = gen.recipe
        assertTrue(r.name.isNotBlank())
        assertTrue(r.steps.size >= 4)
        assertTrue(r.ingredients.size >= 4)
        assertEquals(4, r.servings)
        assertTrue(r.costDollars > 0)
        assertTrue(r.prepMinutes > 0 && r.cookMinutes > 0)
        assertTrue(gen.usedItems.isNotEmpty())
        assertTrue(gen.missingItems.isEmpty())
    }

    @Test
    fun `empty pantry still produces an honest recipe with missing items`() {
        val gen = RecipeMakerEngine.generate(emptyList())
        assertTrue(gen.recipe.name.isNotBlank())
        assertTrue(gen.recipe.steps.isNotEmpty())
        assertTrue(gen.missingItems.isNotEmpty()) // honest: tells you what to buy
    }

    @Test
    fun `same pantry yields the same deterministic recipe`() {
        val pantry = listOf(item("Ground beef"), item("Pasta"), item("Tomato sauce"))
        val a = RecipeMakerEngine.generate(pantry)
        val b = RecipeMakerEngine.generate(pantry)
        assertEquals(a.recipe.name, b.recipe.name)
        assertEquals(a.recipe.steps, b.recipe.steps)
        assertEquals(a.recipe.id, b.recipe.id)
    }

    @Test
    fun `different pantries produce different recipes`() {
        val a = RecipeMakerEngine.generate(listOf(item("Chicken breast"), item("Rice")))
        val b = RecipeMakerEngine.generate(listOf(item("Ground beef"), item("Pasta")))
        assertTrue(a.recipe.name != b.recipe.name)
    }

    @Test
    fun `generated recipe is a fresh id and never collides with bundled ids`() {
        val gen = RecipeMakerEngine.generate(listOf(item("Salmon"), item("Sweet potatoes"), item("Spinach")))
        assertTrue(gen.recipe.id.startsWith("gen-"))
    }
}
