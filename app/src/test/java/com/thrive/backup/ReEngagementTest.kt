package com.thrive.backup

import com.thrive.app.update.ReEngagement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the v1.3.2 re-engagement reminders: correct 42h idle window, cooldown, honest phrases. */
class ReEngagementTest {

    private val now = 1_700_000_000_000L
    private val hour = 60 * 60 * 1000L
    private val idle = 42 * hour
    private val cooldown = 40 * hour

    @Test
    fun `no reminder when the app was never opened`() {
        assertFalse(ReEngagement.shouldRemind(null, null, now))
    }

    @Test
    fun `no reminder inside the 42h window`() {
        assertFalse(ReEngagement.shouldRemind(now - 41 * hour, null, now))
        // Exactly at the boundary it IS due (>= 42h).
        assertTrue(ReEngagement.shouldRemind(now - idle, null, now))
    }

    @Test
    fun `reminder fires after 42h idle when never reminded before`() {
        assertTrue(ReEngagement.shouldRemind(now - 43 * hour, null, now))
        assertTrue(ReEngagement.shouldRemind(now - 10 * idle, null, now))
    }

    @Test
    fun `cooldown prevents nagging within one absence`() {
        // Reminded 10h ago, still away -> quiet until 40h after the last reminder.
        assertFalse(ReEngagement.shouldRemind(now - 43 * hour, now - 10 * hour, now))
        assertFalse(ReEngagement.shouldRemind(now - 100 * hour, now - 39 * hour, now))
    }

    @Test
    fun `another reminder allowed after the cooldown if still away`() {
        assertTrue(ReEngagement.shouldRemind(now - 100 * hour, now - 41 * hour, now))
        // Opening the app resets everything: recent use -> quiet.
        assertFalse(ReEngagement.shouldRemind(now - 5 * hour, now - 50 * hour, now))
    }

    @Test
    fun `phrase list has 10-20 distinct short phrases`() {
        val phrases = ReEngagement.PHRASES
        assertTrue("want 10-20 phrases, got ${phrases.size}", phrases.size in 10..20)
        assertEquals("phrases must be distinct", phrases.size, phrases.distinct().size)
        for (p in phrases) {
            assertTrue("phrase too long (${p.length}): $p", p.length in 3..60)
            assertFalse("no newlines in a notification: $p", p.contains('\n'))
        }
    }

    @Test
    fun `phrase rotation is stable per day and varies across days`() {
        val day = 24 * hour
        val a = ReEngagement.phraseFor(now)
        val b = ReEngagement.phraseFor(now + day)
        val c = ReEngagement.phraseFor(now + 2 * day)
        assertEquals("same day -> same phrase", a, ReEngagement.phraseFor(now))
        assertTrue("different days -> different phrases", b != a)
        assertTrue("third day differs too", c != a)
    }
}
