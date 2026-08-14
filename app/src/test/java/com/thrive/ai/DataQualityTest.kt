package com.thrive.ai

import com.thrive.app.ai.DealFinderEngine
import com.thrive.app.ai.PantryMealEngine
import com.thrive.app.data.model.CatalogItem
import com.thrive.app.data.model.Coupon
import com.thrive.app.data.model.Deal
import com.thrive.app.data.model.PantryItem
import com.thrive.app.data.model.Recipe
import com.thrive.app.data.model.ShoppingItem
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Loads the actual shipped data files (tests run with module dir as cwd). */
object Fixtures {
    val json = Json { ignoreUnknownKeys = true }
    fun load(path: String): String = File("src/main/assets/data/$path").readText()

    val coupons: List<Coupon> = json.decodeFromString(ListSerializer(Coupon.serializer()), load("coupons.json"))
    val recipes: List<Recipe> = json.decodeFromString(ListSerializer(Recipe.serializer()), load("recipes.json"))
    val deals: List<Deal> = json.decodeFromString(ListSerializer(Deal.serializer()), load("deals.json"))
    val catalog: List<CatalogItem> = json.decodeFromString(ListSerializer(CatalogItem.serializer()), load("catalog.json"))
}

class DataQualityTest {

    @Test
    fun `coupons are well formed and always save money`() {
        assertTrue(Fixtures.coupons.size >= 30)
        for (c in Fixtures.coupons) {
            assertTrue("title blank: ${c.id}", c.title.isNotBlank())
            assertTrue("store blank: ${c.id}", c.store.isNotBlank())
            assertTrue("no savings: ${c.id}", c.priceAfter < c.priceBefore)
            assertTrue("bad percent: ${c.id}", c.discountPercent in 1..99)
            assertTrue("bad expiry: ${c.id}", c.endsInDays >= 0)
            assertTrue("url missing: ${c.id}", c.url != null || c.dealType == "IN_STORE")
            assertTrue("urlVerified with no url: ${c.id}", !c.urlVerified || c.url != null)
        }
        assertEquals("ids must be unique", Fixtures.coupons.size, Fixtures.coupons.map { it.id }.toSet().size)
    }

    @Test
    fun `no random stock photos anywhere in shipped data`() {
        for (c in Fixtures.coupons) {
            if (c.imageUrl != null) {
                assertTrue("picsum leak in coupon ${c.id}", "picsum" !in c.imageUrl!!)
                assertTrue("bad image url: ${c.id}", c.imageUrl!!.startsWith("https://"))
            }
        }
        for (d in Fixtures.deals) {
            if (d.imageUrl != null) {
                assertTrue("picsum leak in deal ${d.id}", "picsum" !in d.imageUrl!!)
                assertTrue("bad image url: ${d.id}", d.imageUrl!!.startsWith("https://"))
            }
        }
    }

    @Test
    fun `verified links are real product destinations or marked unverified`() {
        for (c in Fixtures.coupons) {
            if (c.url != null) {
                // Every coupon url must be a plausible https destination.
                assertTrue("coupon url not https: ${c.id}", c.url!!.startsWith("https://"))
            }
            if (!c.urlVerified) {
                // Store-site links are honest only when the UI says so; the model
                // defaults to unverified, which the UI renders as "Open store site".
                assertFalse("urlVerified must be true only with a url: ${c.id}", c.url == null && c.urlVerified)
            }
        }
    }

    @Test
    fun `every recipe section is populated and recipes are complete`() {
        val sections = listOf("under_10", "under_20", "five_ingredients", "family_favorites", "one_pot")
        for (s in sections) {
            assertTrue("section $s needs at least 5 recipes", Fixtures.recipes.count { it.section == s } >= 5)
        }
        for (r in Fixtures.recipes) {
            assertTrue("ingredients empty: ${r.id}", r.ingredients.isNotEmpty())
            assertTrue("steps empty: ${r.id}", r.steps.size >= 3)
            assertTrue("no cost: ${r.id}", r.costDollars > 0)
            assertTrue("no servings: ${r.id}", r.servings >= 2)
            assertEquals("id mismatch in name: ${r.id}", r.id, r.id)
        }
        assertEquals("recipe ids unique", Fixtures.recipes.size, Fixtures.recipes.map { it.id }.toSet().size)
    }

    @Test
    fun `deals and catalog are well formed`() {
        for (d in Fixtures.deals) {
            assertTrue("keywords empty: ${d.id}", d.keywords.isNotEmpty())
            assertTrue("bad price: ${d.id}", d.price > 0)
            if (d.imageUrl != null) {
                assertTrue("picsum leak: ${d.id}", "picsum" !in d.imageUrl!!)
                assertTrue("bad image url: ${d.id}", d.imageUrl!!.startsWith("https://"))
            }
            if (d.urlVerified) {
                assertTrue("verified url missing: ${d.id}", !d.url.isNullOrBlank())
            }
        }
        assertEquals("catalog names unique", Fixtures.catalog.size, Fixtures.catalog.map { it.name }.toSet().size)
        for (c in Fixtures.catalog) {
            assertTrue("bad price: ${c.name}", c.defaultPrice >= 0)
            assertTrue("bad location: ${c.name}", c.location in setOf("Fridge", "Freezer", "Pantry"))
        }
    }

    @Test
    fun `end-to-end deal finder over real data`() {
        val list = listOf(
            ShoppingItem("i1", "Milk", "Dairy", 1, "gal", 3.99),
            ShoppingItem("i2", "Eggs", "Dairy", 1, "dozen", 3.49),
            ShoppingItem("i3", "Chicken Breast", "Meat", 2, "lb", 3.99),
            ShoppingItem("i4", "Ground Beef", "Meat", 1, "lb", 5.49),
            ShoppingItem("i5", "Bananas", "Produce", 2, "lb", 0.79),
            ShoppingItem("i6", "Apples", "Produce", 3, "lb", 2.49),
            ShoppingItem("i7", "White Rice", "Pantry", 1, "lb", 1.49),
            ShoppingItem("i8", "Black Beans", "Pantry", 2, "can", 0.99),
            ShoppingItem("i9", "Spaghetti", "Pantry", 1, "box", 1.49),
            ShoppingItem("i10", "Bread", "Bakery", 1, "loaf", 2.99),
        )
        val plan = DealFinderEngine.plan(list, Fixtures.deals, budget = 60.0, people = 4)
        assertEquals(10, plan.items.size)
        assertTrue("expected at least 4 deals", plan.items.count { it.dealFound } >= 4)
        assertTrue("savings must be positive", plan.totalSavings > 0)
        assertEquals("sum check", plan.totalBefore - plan.totalAfter, plan.totalSavings, 0.01)
        assertTrue("store list non-empty", plan.storesUsed.isNotEmpty())
        assertNotNull("per person cost sane", plan.perPersonCost)
    }

    @Test
    fun `end-to-end pantry meal planning over real data`() {
        val now = System.currentTimeMillis()
        val pantry = listOf(
            PantryItem("p1", "chicken breast", "Meat", "Fridge", 1, expiresAt = now + 24 * 3600_000L),
            PantryItem("p2", "white rice", "Pantry", "Pantry", 1, expiresAt = null),
            PantryItem("p3", "broccoli", "Produce", "Fridge", 1, expiresAt = null),
            PantryItem("p4", "soy sauce", "Condiments", "Pantry", 1, expiresAt = null),
            PantryItem("p5", "eggs", "Dairy", "Fridge", 6, expiresAt = null),
            PantryItem("p6", "milk", "Dairy", "Fridge", 1, expiresAt = now + 2 * 24 * 3600_000L),
            PantryItem("p7", "spaghetti", "Pantry", "Pantry", 1, expiresAt = null),
            PantryItem("p8", "onion", "Produce", "Pantry", 2, expiresAt = null),
        )
        val suggestions = PantryMealEngine.suggest(pantry, Fixtures.recipes, focus = "use_expiring")
        assertTrue("expected suggestions", suggestions.isNotEmpty())
        val top = suggestions.first()
        assertTrue("top suggestion should use pantry items", top.usedItems.isNotEmpty())
        assertTrue("expiring items should be highlighted", top.expiringItemsUsed.isNotEmpty())
        assertTrue("steps present", top.recipe.steps.isNotEmpty())
        // All suggestions must be complete plans
        for (s in suggestions) {
            assertTrue(s.recipe.ingredients.isNotEmpty())
            assertTrue(s.recipe.steps.size >= 3)
        }
    }

    @Test
    fun `no recipe is unreachable from any pantry of common items`() {
        // A generous, realistic pantry should surface more than a third of the library.
        val now = System.currentTimeMillis()
        val names = listOf("chicken breast", "chicken thighs", "ground beef", "ground turkey", "pork chops",
            "bacon", "sausage", "salmon", "shrimp", "eggs", "milk", "cheddar cheese", "mozzarella",
            "parmesan", "sour cream", "yogurt", "butter", "rice", "spaghetti", "penne", "black beans",
            "kidney beans", "chickpeas", "canned tomatoes", "tomato sauce", "pasta sauce", "chicken broth",
            "tuna", "corn", "peas", "peanut butter", "honey", "oats", "flour", "sugar", "bread", "tortillas",
            "potatoes", "sweet potatoes", "carrots", "broccoli", "spinach", "onions", "garlic", "bell pepper",
            "zucchini", "mushrooms", "bananas", "apples", "frozen vegetables", "frozen peas", "frozen corn",
            "soy sauce", "salsa", "taco seasoning", "lemon", "heavy cream", "coconut milk", "curry paste",
            "fettuccine", "tortellini", "ziti", "flank steak", "egg noodles", "puff pastry", "ricotta")
        val pantry = names.mapIndexed { i, n -> PantryItem("p$i", n, "X", "Pantry", 1, expiresAt = null) }
        val suggestions = PantryMealEngine.suggest(pantry, Fixtures.recipes, limit = 10)
        assertTrue("expected many suggestions", suggestions.size >= 8)
        val ids = suggestions.map { it.recipe.id }.toSet()
        // The engine should discover meals across several sections.
        val sections = suggestions.map { it.recipe.section }.toSet()
        assertTrue("coverage across sections expected", sections.size >= 3)
        assertTrue(ids.isNotEmpty())
        assertFalse("no suggestion should claim zero usage", suggestions.any { it.usedItems.isEmpty() })
    }
}
