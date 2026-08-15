package com.thrive.backup

import com.thrive.app.data.model.Coupon
import com.thrive.app.ui.savings.pickDailyPick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the "deal of the day" hero against the small-feed crash: any catalog
 * size (zero, one, two, three, or thousands) must select safely and
 * deterministically for a given day.
 */
class DailyPickTest {

    private fun coupon(
        id: String,
        before: Double,
        after: Double,
        endsInDays: Int = 7,
        isNew: Boolean = false,
    ) = Coupon(
        id = id,
        store = "Store",
        title = "Deal $id",
        description = "",
        category = "Grocery",
        priceBefore = before,
        priceAfter = after,
        endsInDays = endsInDays,
        isNew = isNew,
    )

    /** The three strongest offers for the fixtures below, in any order. */
    private val strongest = setOf("best", "second", "third")

    private fun catalog(): List<Coupon> = listOf(
        coupon("weak1", before = 2.0, after = 1.9),                 // ~5% off
        coupon("weak2", before = 2.0, after = 1.95),                // ~2% off
        coupon("weak3", before = 3.0, after = 2.9),                 // ~3% off
        coupon("best", before = 10.0, after = 2.0, endsInDays = 1), // 80% off, urgent
        coupon("second", before = 10.0, after = 3.0, endsInDays = 2), // 70% off, urgent
        coupon("third", before = 20.0, after = 8.0, isNew = true),  // 60% off, fresh
        coupon("weak4", before = 5.0, after = 4.9),
    )

    @Test
    fun `empty feed yields no pick`() {
        assertNull(pickDailyPick(emptyList(), day = 1))
    }

    @Test
    fun `single item feed always picks that item`() {
        val only = coupon("only", before = 5.0, after = 2.0)
        for (day in 0..400) {
            assertEquals("only", pickDailyPick(listOf(only), day)?.id)
        }
    }

    @Test
    fun `two item feed never throws and picks a member of the feed`() {
        val two = listOf(coupon("a", before = 5.0, after = 2.0), coupon("b", before = 6.0, after = 3.0))
        val ids = two.map { it.id }.toSet()
        for (day in 0..400) {
            val pick = pickDailyPick(two, day)
            assertNotNull(pick)
            assertTrue(pick!!.id in ids)
        }
    }

    @Test
    fun `three item feed never throws and picks a member of the feed`() {
        // a = 60% off, c = 55% off, b = 50% off; with exactly three the hero
        // rotates among all of them (the "top three" is the whole feed).
        val three = listOf(coupon("a", before = 5.0, after = 2.0), coupon("b", before = 6.0, after = 3.0), coupon("c", before = 9.0, after = 4.0))
        val ids = three.map { it.id }.toSet()
        for (day in 0..400) {
            val pick = pickDailyPick(three, day)
            assertNotNull(pick)
            assertTrue("pick ${pick!!.id} should be a feed member", pick.id in ids)
        }
    }

    @Test
    fun `large feed always picks one of the strongest three`() {
        val cat = catalog()
        for (day in 0..400) {
            val pick = pickDailyPick(cat, day)
            assertNotNull(pick)
            assertTrue("pick ${pick!!.id} should be a top offer", pick.id in strongest)
        }
    }

    @Test
    fun `selection is deterministic for a fixed day`() {
        val cat = catalog()
        val first = pickDailyPick(cat, day = 123)
        repeat(5) {
            assertEquals(first?.id, pickDailyPick(cat, day = 123)?.id)
        }
    }

    @Test
    fun `selection rotates across days`() {
        val cat = catalog()
        val picked = (0..10).map { pickDailyPick(cat, it)?.id }.toSet()
        assertTrue("hero should rotate among top offers, got $picked", picked.size > 1)
    }
}
