package com.thrive.backup

import com.thrive.app.data.model.Coupon
import com.thrive.app.data.remote.NearbyStore
import com.thrive.app.data.remote.SyncState
import com.thrive.app.ui.savings.SavingsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the v1.3.7 Savings upgrades: brand/category search, savings-ranked
 * results, store grouping by distance, a strong daily pick, and the fresh
 * "new this week" shelf. */
class SavingsSearchTest {

    private fun coupon(
        id: String,
        store: String = "Walmart",
        title: String,
        category: String = "Grocery",
        before: Double = 10.0,
        after: Double = 5.0,
        brand: String? = null,
        isNew: Boolean = false,
        endsInDays: Int = 7,
    ) = Coupon(
        id = id, store = store, title = title, description = title,
        category = category, priceBefore = before, priceAfter = after,
        brand = brand, endsInDays = endsInDays, isNew = isNew,
    )

    private fun state(coupons: List<Coupon>, sync: SyncState = SyncState()) =
        SavingsUiState(coupons = coupons, sync = sync)

    @Test
    fun `search matches brand and category, not just title`() {
        val coupons = listOf(
            coupon("1", title = "Paper Towels, 6 rolls", brand = "Kirkland Signature", before = 22.99, after = 18.99),
            coupon("2", title = "Organic Strawberries, 1 lb", category = "Produce", before = 4.99, after = 2.99),
        )
        val s = state(coupons)
        assertEquals(setOf("1"), s.copy(query = "kirkland").filtered.map { it.id }.toSet())
        assertEquals(setOf("2"), s.copy(query = "produce").filtered.map { it.id }.toSet())
    }

    @Test
    fun `search results are ranked by savings percent then absolute`() {
        val coupons = listOf(
            coupon("small", title = "Great Value Milk, 1 gallon", before = 3.98, after = 3.48, brand = "Great Value"),   // 13%
            coupon("big", title = "Great Value Milk, 1 gallon", before = 3.98, after = 2.98, brand = "Great Value"),     // 25%
            coupon("bigger", title = "Great Value Milk, 1 gallon", before = 5.98, after = 2.98, brand = "Great Value"),  // 50%
        )
        val results = state(coupons).copy(query = "milk").filtered.map { it.id }
        assertEquals(listOf("bigger", "big", "small"), results)
    }

    @Test
    fun `no query keeps the catalog order untouched`() {
        val coupons = listOf(
            coupon("a", title = "Milk", before = 4.0, after = 1.0),
            coupon("b", title = "Eggs", before = 4.0, after = 3.99),
        )
        assertEquals(listOf("a", "b"), state(coupons).filtered.map { it.id })
    }

    @Test
    fun `stores are grouped and sorted by nearest distance`() {
        val coupons = listOf(
            coupon("1", store = "Costco", title = "Eggs", before = 5.99, after = 4.79),
            coupon("2", store = "Walmart", title = "Milk", before = 3.98, after = 2.98),
            coupon("3", store = "Costco", title = "Bacon", before = 14.99, after = 11.99),
        )
        val sync = SyncState(nearbyStores = listOf(
            NearbyStore(store = "Costco", city = "Springfield", distMi = 1.2),
            NearbyStore(store = "Walmart", city = "Springfield", distMi = 4.8),
        ))
        val sections = state(coupons, sync).storeSections
        assertEquals(listOf("Costco", "Walmart"), sections.map { it.store })
        assertEquals(2, sections[0].coupons.size)
        assertEquals(1.2, sections[0].distMi!!, 0.001)
    }

    @Test
    fun `stores fall back to alphabetical order without location`() {
        val coupons = listOf(
            coupon("1", store = "Zebra Foods", title = "A", before = 1.0, after = 0.5),
            coupon("2", store = "Aldi", title = "B", before = 1.0, after = 0.5),
        )
        assertEquals(listOf("Aldi", "Zebra Foods"), state(coupons).storeSections.map { it.store })
    }

    @Test
    fun `store sections respect the category filter`() {
        val coupons = listOf(
            coupon("1", store = "Walmart", title = "Milk", category = "Grocery", before = 3.98, after = 2.98),
            coupon("2", store = "Walmart", title = "TV", category = "Tech", before = 199.0, after = 149.0),
        )
        val s = state(coupons).copy(category = "Tech")
        assertEquals(listOf("Walmart"), s.storeSections.map { it.store })
        assertEquals(listOf("2"), s.storeSections[0].coupons.map { it.id })
    }

    @Test
    fun `daily pick is deterministic and one of the strongest deals`() {
        val coupons = (1..50).map {
            coupon(
                id = "c$it",
                title = "Product $it",
                before = 10.0 + it,
                after = 9.0 + it * 0.5, // discount shrinks as it grows — c1 is strongest
            )
        }
        val s = state(coupons)
        val pick = s.dailyPick!!
        // Strongest deal (c1) or one of its nearest rivals — never a weak tail.
        val strength = coupons.associate { c -> c.id to (c.discountPercent + (c.priceBefore - c.priceAfter) / 10.0) }
        val max = strength.values.maxOrNull()!!
        assertTrue("daily pick must be near the strongest: ${pick.id} vs $max", strength.getValue(pick.id) >= max - 15)
        // Same day => same pick (deterministic).
        assertEquals(pick.id, s.dailyPick!!.id)
    }

    @Test
    fun `new this week lists fresh deals first by expiry`() {
        val fresh = listOf(
            coupon("f1", title = "Fresh B", isNew = true, endsInDays = 9),
            coupon("f2", title = "Fresh A", isNew = true, endsInDays = 2),
        )
        val old = listOf(coupon("o1", title = "Old", isNew = false, endsInDays = 1))
        val s = state(fresh + old)
        assertEquals(listOf("f2", "f1"), s.newThisWeek.map { it.id })
        assertTrue(s.newThisWeek.all { it.isNew })
    }

    @Test
    fun `new this week falls back to soonest-expiring when nothing is flagged`() {
        val coupons = listOf(
            coupon("b", title = "B", endsInDays = 5),
            coupon("a", title = "A", endsInDays = 1),
        )
        assertEquals(listOf("a", "b"), state(coupons).newThisWeek.map { it.id })
    }
}
