package com.thrive.backup

import com.thrive.app.data.remote.SyncState
import com.thrive.app.data.remote.SyncStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the honest feed labeling (v1.2.11): "Live" must only ever show when
 * the coupon list on screen actually came from the server. A reachable server
 * that sent no coupons, or a dead server after a previous live sync, is the
 * bundled feed — not live.
 */
class FeedOriginTest {

    @Test
    fun `live feed requires OK status AND server coupons`() {
        assertTrue(SyncState(status = SyncStatus.OK, feedOrigin = "live").hasLiveFeed)
        assertTrue(SyncState(status = SyncStatus.OK, feedOrigin = "live", lastSyncedAt = 1000L).hasLiveFeed)
    }

    @Test
    fun `OK status with bundled origin is not live`() {
        // Server reachable but sent no coupons -> bundled estimates on screen.
        assertFalse(SyncState(status = SyncStatus.OK, feedOrigin = "bundled").hasLiveFeed)
    }

    @Test
    fun `error after a previous live sync still labels bundled`() {
        // The server died since the last good sync: coupons shown are stale
        // bundled/local data, never claim "Live".
        assertFalse(SyncState(status = SyncStatus.ERROR, feedOrigin = "bundled").hasLiveFeed)
        assertFalse(SyncState(status = SyncStatus.ERROR, feedOrigin = "bundled", lastSyncedAt = 5_000L).hasLiveFeed)
    }

    @Test
    fun `offline and syncing are never live`() {
        assertFalse(SyncState(status = SyncStatus.OFFLINE, feedOrigin = "bundled").hasLiveFeed)
        assertFalse(SyncState(status = SyncStatus.SYNCING, feedOrigin = "live").hasLiveFeed)
    }
}
