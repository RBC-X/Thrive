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

    @Test
    fun `different variants roll different recipes for try another`() {
        val pantry = listOf(item("Chicken breast"), item("Rice"), item("Broccoli"))
        val names = (0 until 8).map { RecipeMakerEngine.generate(pantry, variant = it).recipe.name }
        // 8 distinct blueprints from the same pantry — never 4 renames of one dish.
        assertEquals("each variant should be its own recipe: $names", 8, names.toSet().size)
        // Same variant stays stable across calls.
        assertEquals(names.first(), RecipeMakerEngine.generate(pantry, variant = 0).recipe.name)
    }

    @Test
    fun `variants differ in more than just the cooking-method suffix`() {
        val pantry = listOf(item("Chicken breast"), item("Rice"), item("Broccoli"))
        val a = RecipeMakerEngine.generate(pantry, variant = 0)
        val b = RecipeMakerEngine.generate(pantry, variant = 1)
        // The dish identity (method + flavor + name) differs, not just a tag.
        val tagsA = a.recipe.tags.toSet()
        val tagsB = b.recipe.tags.toSet()
        assertTrue("flavors should differ across variants", tagsA != tagsB)
        assertTrue("full recipe objects should differ", a.recipe.steps != b.recipe.steps)
    }

    @Test
    fun `dozens of try-another rolls stay diverse`() {
        val pantry = listOf(
            item("Chicken breast"), item("Ground beef"), item("Rice"), item("Pasta"),
            item("Broccoli"), item("Carrots"), item("Salsa"), item("Soy sauce"), item("Cheese"),
        )
        val names = (0 until 16).map { RecipeMakerEngine.generate(pantry, variant = it).recipe.name }
        assertEquals("16 variants should give 16 distinct recipes: $names", 16, names.toSet().size)
    }

    @Test
    fun `rotates through multiple pantry items instead of always the first`() {
        // Two proteins, two starches, two veggies in the pantry.
        val pantry = listOf(
            item("Chicken breast"), item("Ground beef"),
            item("Rice"), item("Pasta"),
            item("Broccoli"), item("Carrots"),
        )
        val first = RecipeMakerEngine.generate(pantry, variant = 0)
        // A later variant (item rotation) should lead with a different protein/starch.
        val allNames = (0 until 8).map { RecipeMakerEngine.generate(pantry, variant = it).recipe.name }
        assertTrue("should use more than the first protein", allNames.any { it.contains("Beef") })
        assertTrue("should use more than the first starch", allNames.any { it.contains("Pasta") })
        assertTrue("should use more than the first veggie", allNames.any { it.contains("Carrots") })
        // At least one variant used a pantry sauce (salsa/soy), not a default.
        val withSaucePantry = RecipeMakerEngine.generate(
            listOf(item("Chicken breast"), item("Rice"), item("Salsa")), variant = 0,
        )
        assertTrue(withSaucePantry.recipe.name.contains("Salsa"))
    }

    @Test
    fun `uses list is honest - only pantry items, never the default sauce`() {
        // Pantry has chicken only; salsa is the flavor default, not owned.
        val gen = RecipeMakerEngine.generate(listOf(item("Chicken breast")), variant = 0)
        assertTrue("chicken should be used", gen.usedItems.contains("chicken breast"))
        assertTrue("default sauce must not be claimed as owned: ${gen.usedItems}",
            gen.usedItems.none { it.contains("salsa", ignoreCase = true) })
        // But a pantry with real salsa gets it listed as used.
        val withSalsa = RecipeMakerEngine.generate(
            listOf(item("Chicken breast"), item("Salsa")), variant = 0,
        )
        assertTrue("pantry salsa should be claimed: ${withSalsa.usedItems}",
            withSalsa.usedItems.any { it.contains("salsa", ignoreCase = true) })
    }

    @Test
    fun `uses a second vegetable when the pantry has two`() {
        val gen = RecipeMakerEngine.generate(
            listOf(item("Chicken breast"), item("Rice"), item("Broccoli"), item("Carrots")),
            variant = 0,
        )
        // Either the recipe name or the ingredients should show both veggies.
        val nameLower = gen.recipe.name.lowercase()
        val ingLower = gen.recipe.ingredients.joinToString { it.name }.lowercase()
        assertTrue("both veggies should appear: $nameLower | $ingLower",
            (nameLower.contains("broccoli") || ingLower.contains("broccoli")) &&
                (nameLower.contains("carrot") || ingLower.contains("carrot")))
    }

    @Test
    fun `missing items map to concrete buyable shopping entries`() {
        val gen = RecipeMakerEngine.generate(listOf(item("Chicken breast"))) // no starch, no veg
        assertTrue(gen.missingItems.isNotEmpty())
        assertTrue(gen.missingToBuy.isNotEmpty())
        gen.missingToBuy.forEach { (name, category, label) ->
            assertTrue(name.isNotBlank())
            assertTrue(category.isNotBlank())
            assertTrue(label.isNotBlank())
        }
        // The chicken IS in the pantry — never offered for re-purchase.
        assertTrue(gen.missingToBuy.none { it.first.contains("chicken", ignoreCase = true) })
    }

    @Test
    fun `complete pantry offers nothing to buy`() {
        val gen = RecipeMakerEngine.generate(listOf(
            item("Chicken breast"), item("Rice"), item("Broccoli"), item("Salsa"), item("Cheese"),
        ))
        assertEquals(emptyList<String>(), gen.missingItems)
        assertEquals(emptyList<Triple<String, String, String>>(), gen.missingToBuy)
    }
}
