package com.thrive.ai

import com.thrive.app.ai.DealFinderEngine
import com.thrive.app.ai.PlanIngredientLine
import com.thrive.app.ai.PlanShoppingGroup
import com.thrive.app.ai.WeeklyPlan
import com.thrive.app.data.model.Deal
import com.thrive.app.data.model.ShoppingItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DealFinderEngineTest {

    private fun deal(id: String, name: String, cat: String, price: Double, kws: List<String>) = Deal(
        id = id, store = "Kroger", productName = name, category = cat,
        price = price, keywords = kws,
    )

    private fun item(name: String, cat: String, price: Double) = ShoppingItem(
        id = name, name = name, category = cat, quantity = 1, estPrice = price,
    )

    private val deals = listOf(
        deal("d1", "Kellogg's Corn Flakes 18oz", "Grocery", 3.99, listOf("cereal", "corn flakes")),
        deal("d2", "Great Value Spaghetti 16oz", "Grocery", 0.98, listOf("pasta", "spaghetti")),
        deal("d3", "Whole Milk 1 Gallon", "Grocery", 2.89, listOf("milk")),
    )

    // ---------------------------------------------------------------------
    // Weekly-plan trip matching (tripFor)
    // ---------------------------------------------------------------------

    private fun planWith(vararg lines: Triple<String, Double, String>) = WeeklyPlan(
        nights = emptyList(),
        budget = 60.0,
        people = 2,
        recipeCost = 0.0,
        extraCost = 30.0,
        combinedShopping = emptyList(),
        shoppingGroups = listOf(
            PlanShoppingGroup(
                category = "Meat & Seafood",
                items = lines.map { (n, q, u) -> PlanIngredientLine(n, q, u, q * 5.0, 1) },
                subtotal = lines.sumOf { it.first.length.toDouble() },
            ),
        ),
    )

    @Test
    fun `tripFor groups matched plan lines by store with real prices`() {
        val plan = planWith(Triple("chicken breast", 2.0, "lb"))
        val deals = listOf(
            deal("k1", "Kroger Chicken Breast 2 lb", "Grocery", 6.98, listOf("chicken", "breast")).let {
                it.copy(store = "Kroger", size = "2 lb")
            },
        )
        val trip = DealFinderEngine.tripFor(plan, deals)!!
        val item = trip.items.single()
        assertTrue(item.dealFound)
        assertEquals("Kroger", item.store)
        assertEquals("k1", item.dealId)
        // Deal price is used where it beats the estimate (2 lb for $6.98 vs $4.00/lb estimate).
        assertEquals(6.98, trip.totalAfter, 0.001)
        val kroger = trip.storeGroups.single { it.store == "Kroger" }
        assertEquals(6.98, kroger.subtotal, 0.001)
        assertEquals(1, trip.storesUsed.size)
    }

    @Test
    fun `tripFor never matches by category alone and marks unmatched honestly`() {
        // Only a coconut-milk deal exists; the plan needs plain milk. The
        // identity-changer guard must keep them apart even though both are
        // "Grocery" after the aisle bridge.
        val plan = planWith(Triple("milk", 1.0, "gal"))
        val deals = listOf(
            deal("cm", "Coconut Milk 32 oz", "Grocery", 2.49, listOf("coconut", "milk")).let {
                it.copy(store = "Whole Foods", size = "32 floz")
            },
        )
        val trip = DealFinderEngine.tripFor(plan, deals)!!
        val item = trip.items.single()
        assertFalse(item.dealFound)
        assertEquals("Any store", item.store)
        // Still estimated, still honest — never a fake match.
        assertEquals(item.item.estPrice, trip.totalAfter, 0.001)
        assertTrue(trip.totalSavings == 0.0)
    }

    @Test
    fun `tripFor skips pantry-owned lines`() {
        val plan = WeeklyPlan(
            nights = emptyList(),
            budget = 60.0,
            people = 2,
            recipeCost = 0.0,
            extraCost = 10.0,
            combinedShopping = emptyList(),
            shoppingGroups = listOf(
                PlanShoppingGroup(
                    "Dairy & Eggs",
                    listOf(
                        PlanIngredientLine("eggs", 1.0, "dozen", 3.49, 1, haveInPantry = true),
                        PlanIngredientLine("butter", 1.0, "lb", 4.29, 1),
                    ),
                    7.78,
                ),
            ),
        )
        val trip = DealFinderEngine.tripFor(plan, emptyList())!!
        assertEquals(1, trip.items.size)
        assertEquals("butter", trip.items.single().item.name)
    }

    @Test
    fun `tripFor returns null when there is nothing to buy`() {
        val plan = WeeklyPlan(
            nights = emptyList(), budget = 60.0, people = 2,
            recipeCost = 0.0, extraCost = 0.0, combinedShopping = emptyList(),
        )
        assertNull(DealFinderEngine.tripFor(plan, emptyList()))
    }

    @Test
    fun `best deal found for matching item`() {
        val r = DealFinderEngine.bestDealFor(item("Cereal", "Grocery", 5.49), deals)
        assertEquals("d1", r?.deal?.id)
        assertFalse(r!!.unitMatched)
    }

    @Test
    fun `bulk deal wins on per-unit price`() {
        // 2 lb for $6.98 = $3.49/lb beats the $3.99/lb estimate.
        val chicken = ShoppingItem("c", "Chicken Breast", "Meat", quantity = 2, unit = "lb", estPrice = 3.99)
        val bulk = listOf(
            Deal("b1", "Walmart", "Chicken Breast 2 lb", "Meat", 6.98, size = "2 lb", keywords = listOf("chicken breast")),
        )
        val r = DealFinderEngine.bestDealFor(chicken, bulk)
        assertEquals("b1", r?.deal?.id)
        assertTrue(r!!.unitMatched)
        assertEquals(3.49, r.price, 0.001)
    }

    @Test
    fun `per-unit savings scale by quantity`() {
        val apples = ShoppingItem("a", "Apples", "Produce", quantity = 3, unit = "lb", estPrice = 2.49)
        val bulk = listOf(
            Deal("ap1", "Walmart", "Gala Apples 3 lb", "Produce", 2.97, size = "3 lb", keywords = listOf("apples", "gala")),
        )
        val plan = DealFinderEngine.plan(listOf(apples), bulk, budget = 20.0, people = 2)
        val r = plan.items.first()
        assertTrue(r.dealFound)
        assertTrue(r.unitMatched)
        assertEquals(7.47, plan.totalBefore, 0.001)
        assertEquals(2.97, plan.totalAfter, 0.001)
        assertEquals(4.50, plan.totalSavings, 0.001)
    }

    @Test
    fun `eggs compared across ct and dozen units`() {
        val eggs = ShoppingItem("e", "Eggs", "Dairy", quantity = 1, unit = "dozen", estPrice = 3.49)
        val bulk = listOf(
            Deal("eg1", "Aldi", "Large Eggs 12 ct", "Dairy", 2.89, size = "12 ct", keywords = listOf("eggs")),
        )
        val r = DealFinderEngine.bestDealFor(eggs, bulk)
        assertTrue(r!!.unitMatched)
        assertEquals(2.89, r.price, 0.001)
    }

    @Test
    fun `mismatched units fall back to whole-price compare`() {
        // Item is a per-bottle estimate; deal is priced per 24 oz — no fair unit compare,
        // so it only applies when the whole price is lower.
        val soap = ShoppingItem("s", "Dish Soap", "Household", quantity = 1, unit = "bottle", estPrice = 2.49)
        val expensive = listOf(
            Deal("so1", "Kroger", "Dish Soap 24 oz", "Household", 3.99, size = "24 oz", keywords = listOf("dish soap")),
        )
        val r = DealFinderEngine.bestDealFor(soap, expensive)
        assertNull(r)
    }

    @Test
    fun `per-unit worse deal does not apply`() {
        // 1 lb for $4.99 is worse than the $3.99/lb estimate.
        val beef = ShoppingItem("gb", "Ground Beef", "Meat", quantity = 1, unit = "lb", estPrice = 3.99)
        val bulk = listOf(
            Deal("gb1", "Kroger", "Ground Beef 1 lb", "Meat", 4.99, size = "1 lb", keywords = listOf("ground beef")),
        )
        val r = DealFinderEngine.bestDealFor(beef, bulk)
        assertNull(r)
    }

    @Test
    fun `no deal falls back to original price`() {
        val plan = DealFinderEngine.plan(
            listOf(item("Bananas", "Produce", 1.99)),
            deals, budget = 50.0, people = 2,
        )
        assertEquals("Any store", plan.items.first().store)
        assertFalse(plan.items.first().dealFound)
        assertEquals(1.99, plan.totalAfter, 0.001)
    }

    @Test
    fun `organic qualifier must appear in the deal`() {
        // A list item for organic milk must NOT be satisfied by a plain milk deal.
        val organicMilk = ShoppingItem("om", "Organic Milk", "Dairy", 1, "gal", 3.99)
        val plainMilk = listOf(
            Deal("pm", "Kroger", "Milk 1 Gallon", "Dairy", 2.89, size = "1 gal", keywords = listOf("milk")),
        )
        assertNull(DealFinderEngine.bestDealFor(organicMilk, plainMilk))
    }

    @Test
    fun `plain item may match an organic deal`() {
        // But a plain milk item can take an organic-milk deal (it is still milk),
        // as long as every item token appears in the deal.
        val plainMilk = ShoppingItem("pm", "Milk", "Dairy", 1, "gal", 3.99)
        val organicDeal = listOf(
            Deal("od", "Aldi", "Organic Milk 1 Gallon", "Dairy", 3.49, size = "1 gal", keywords = listOf("milk")),
        )
        val r = DealFinderEngine.bestDealFor(plainMilk, organicDeal)
        assertEquals("od", r?.deal?.id)
    }

    @Test
    fun `identity changer blocks broad-word match`() {
        // "Milk" must not match "Coconut Milk" — coconut changes the product.
        val milk = ShoppingItem("mk", "Milk", "Dairy", 1, "gal", 3.99)
        val coconut = listOf(
            Deal("cm", "Kroger", "Coconut Milk 1 gal", "Dairy", 2.49, size = "1 gal", keywords = listOf("coconut milk")),
        )
        assertNull(DealFinderEngine.bestDealFor(milk, coconut))
    }

    @Test
    fun `single broad word is never enough`() {
        // "Strawberries" shares only a broad word with "Organic Strawberries" if the
        // item also asks for something the deal lacks; and the reverse must fail too.
        val organicStrawberries = ShoppingItem("os", "Organic Strawberries", "Produce", 1, "pack", 4.99)
        val plainStrawberries = listOf(
            Deal("ps", "Kroger", "Strawberries 1 lb", "Produce", 3.49, size = "1 lb", keywords = listOf("strawberries")),
        )
        assertNull(DealFinderEngine.bestDealFor(organicStrawberries, plainStrawberries))
    }

    @Test
    fun `unmatched item keeps estimate and reports honestly`() {
        val plan = DealFinderEngine.plan(
            listOf(ShoppingItem("z", "Saffron Threads", "Pantry", 1, "oz", 9.99)),
            deals, budget = 20.0, people = 1,
        )
        val r = plan.items.first()
        assertFalse(r.dealFound)
        assertEquals(9.99, r.price, 0.001)
        assertEquals(9.99, plan.totalAfter, 0.001)
        assertEquals(0.0, plan.totalSavings, 0.001)
    }

    @Test
    fun `store groups subtotal matched items per store`() {
        val plan = DealFinderEngine.plan(
            listOf(item("Cereal", "Grocery", 5.49), item("Milk", "Grocery", 3.49)),
            deals, budget = 20.0, people = 2,
        )
        val kroger = plan.storeGroups.firstOrNull { it.store == "Kroger" }
        assertNotNull(kroger)
        assertEquals(2, kroger!!.items.size)
        assertEquals(6.88, kroger.subtotal, 0.001)
        assertEquals(plan.totalAfter, plan.storeGroups.sumOf { it.subtotal }, 0.001)
    }

    @Test
    fun `trip plan computes savings and totals`() {
        val plan = DealFinderEngine.plan(
            listOf(item("Cereal", "Grocery", 5.49), item("Milk", "Grocery", 3.49)),
            deals, budget = 20.0, people = 4,
        )
        assertEquals(8.98, plan.totalBefore, 0.001)
        assertEquals(6.88, plan.totalAfter, 0.001)
        assertEquals(2.10, plan.totalSavings, 0.001)
        assertEquals("UNDER_BUDGET", plan.status)
        assertEquals(4, plan.people)
        assertEquals(1.72, plan.perPersonCost, 0.02)
    }

    @Test
    fun `over budget trip reports overshoot`() {
        val plan = DealFinderEngine.plan(
            listOf(item("Cereal", "Grocery", 5.49), item("Milk", "Grocery", 3.49)),
            deals, budget = 5.0, people = 1,
        )
        assertEquals("OVER_BUDGET", plan.status)
        assertEquals(1.88, plan.overshoot, 0.02)
        assertTrue(plan.isOverBudget)
    }

    @Test
    fun `brand name items get swap suggestions`() {
        val plan = DealFinderEngine.plan(
            listOf(item("Kellogg's Corn Flakes", "Grocery", 5.49)),
            deals, budget = 20.0, people = 2,
        )
        assertTrue(plan.swaps.isNotEmpty())
        assertTrue(plan.swaps.first().saves > 0)
    }

    @Test
    fun `quantity multiplies into totals`() {
        val items = listOf(item("Milk", "Grocery", 3.49).copy(quantity = 2))
        val plan = DealFinderEngine.plan(items, deals, budget = 20.0, people = 2)
        assertEquals(6.98, plan.totalBefore, 0.001)
        assertEquals(5.78, plan.totalAfter, 0.001)
    }

    @Test
    fun `matched item exposes the real product and deal info`() {
        val chicken = ShoppingItem("c", "Chicken Breast", "Meat", 2, "lb", 3.99)
        val r = DealFinderEngine.bestDealFor(
            chicken,
            listOf(
                Deal("b1", "Walmart", "Boneless Chicken Breast 2 lb", "Meat", 6.98, size = "2 lb",
                    keywords = listOf("chicken breast"), url = "https://walmart.com/p/1", urlVerified = true,
                    brand = "Great Value", imageUrl = "https://img.example/1.jpg"),
            ),
        )
        val plan = DealFinderEngine.plan(listOf(chicken), listOf(r!!.deal), 20.0, 2)
        val resolved = plan.items.first()
        assertTrue(resolved.dealFound)
        assertEquals("Boneless Chicken Breast 2 lb", resolved.matchedName)
        assertEquals("Great Value", resolved.dealBrand)
        assertEquals("https://walmart.com/p/1", resolved.dealUrl)
        assertTrue(resolved.dealUrlVerified)
        assertEquals("https://img.example/1.jpg", resolved.dealImageUrl)
        assertEquals(3.49, resolved.price, 0.001)
    }
}
