package com.thrive.backup

import com.thrive.app.ai.PlanIntentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the deterministic NL → structured planning parser: people, night
 * count, budget, plan style, store, restrictions, and cook-time caps must be
 * extracted honestly (never guessed) from a plain-English sentence.
 */
class PlanIntentParserTest {

    @Test
    fun `full sentence parses people nights budget focus and store`() {
        val p = PlanIntentParser.parse("dinner for two, five nights, under \$70, high protein, mostly Aldi")
        val r = p.request
        assertEquals(2, r.people)
        assertEquals(5, r.nights)
        assertEquals(70.0, r.budget, 0.001)
        assertEquals("high_protein", r.focus)
        assertEquals("Aldi", r.preferredStore)
    }

    @Test
    fun `allergy phrasing maps to restriction keys`() {
        val p = PlanIntentParser.parse("family of four, peanut allergy, no shellfish, seven nights")
        assertTrue("peanut" in p.request.restrictions)
        assertTrue("shellfish" in p.request.restrictions)
    }

    @Test
    fun `cook time cap detected`() {
        val p = PlanIntentParser.parse("quick meals under 30 minutes for the week")
        assertEquals(30, p.request.maxCookMinutes)
    }

    @Test
    fun `blank input returns default request with honest note`() {
        val p = PlanIntentParser.parse("   ")
        assertEquals(4, p.request.people)
        assertEquals(7, p.request.nights)
        assertTrue(p.notes.isNotEmpty())
    }

    @Test
    fun `unknown text keeps defaults and admits nothing was understood`() {
        val p = PlanIntentParser.parse("please do something wonderful")
        assertTrue(p.matched.isEmpty())
        assertEquals(7, p.request.nights)
        assertEquals(75.0, p.request.budget, 0.001)
    }

    @Test
    fun `word numbers work for people`() {
        val p = PlanIntentParser.parse("dinner for three under \$50")
        assertEquals(3, p.request.people)
        assertEquals(50.0, p.request.budget, 0.001)
    }

    @Test
    fun `appliance hints are captured`() {
        val p = PlanIntentParser.parse("dinners with the air fryer and slow cooker")
        assertTrue("air fryer" in p.request.appliances)
        assertTrue("slow cooker" in p.request.appliances)
    }

    @Test
    fun `vegan request applies the vegan restriction`() {
        val p = PlanIntentParser.parse("vegan dinners for the week under \$80")
        assertTrue("vegan" in p.request.restrictions)
    }
}
