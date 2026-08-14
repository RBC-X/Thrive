package com.thrive.backup

import com.thrive.app.update.GithubUpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Guards the one-tap "Connect to public backup server" discovery (v1.2.10). */
class SyncUrlTest {

    @Test
    fun `accepts https urls and strips trailing slash`() {
        assertEquals(
            "https://abc.trycloudflare.com",
            GithubUpdateChecker.sanitizeSyncUrl("https://abc.trycloudflare.com/"),
        )
        assertEquals(
            "https://abc.trycloudflare.com",
            GithubUpdateChecker.sanitizeSyncUrl("  https://abc.trycloudflare.com  "),
        )
    }

    @Test
    fun `rejects http plaintext and non-http garbage`() {
        assertNull(GithubUpdateChecker.sanitizeSyncUrl("http://abc.trycloudflare.com"))
        assertNull(GithubUpdateChecker.sanitizeSyncUrl("10.0.2.2:4000"))
        assertNull(GithubUpdateChecker.sanitizeSyncUrl("ftp://abc"))
        assertNull(GithubUpdateChecker.sanitizeSyncUrl(""))
    }

    @Test
    fun `rejects oversized urls`() {
        val huge = "https://" + "a".repeat(600) + ".trycloudflare.com"
        assertNull(GithubUpdateChecker.sanitizeSyncUrl(huge))
    }
}
