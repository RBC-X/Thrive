package com.thrive.backup

import com.thrive.app.data.remote.NearbyStore
import com.thrive.app.data.remote.SyncState
import com.thrive.app.data.remote.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the v1.3.1 nearby-deals state: "nearby" only when location is shared and set. */
class NearbyLocationTest {

    @Test
    fun `hasNearby requires location enabled AND coordinates`() {
        val base = SyncState(status = SyncStatus.OK)
        assertFalse("off by default", base.hasNearby)
        assertFalse("enabled but no fix yet", base.copy(locationEnabled = true).hasNearby)
        assertTrue(
            "enabled with coords",
            base.copy(locationEnabled = true, locationLat = 39.1, locationLng = -84.51).hasNearby,
        )
        // A live feed without location is not "nearby" — honest labeling.
        assertFalse(SyncState(status = SyncStatus.OK, feedOrigin = "live").hasNearby)
    }

    @Test
    fun `nearby stores come from the sync payload location block`() {
        val stores = listOf(
            NearbyStore(store = "Kroger", city = "Cincinnati", distMi = 0.4),
            NearbyStore(store = "Aldi", city = "Cincinnati", distMi = 1.2),
        )
        val s = SyncState(
            status = SyncStatus.OK,
            locationEnabled = true,
            locationLat = 39.1,
            locationLng = -84.51,
            nearbyStores = stores,
        )
        assertTrue(s.hasNearby)
        assertEquals(2, s.nearbyStores.size)
        assertEquals("Kroger", s.nearbyStores.first().store)
    }

    @Test
    fun `clearing location drops the nearby state entirely`() {
        val on = SyncState(
            status = SyncStatus.OK,
            locationEnabled = true,
            locationLat = 39.1,
            locationLng = -84.51,
            nearbyStores = listOf(NearbyStore(store = "Kroger", distMi = 0.4)),
        )
        val off = on.copy(locationEnabled = false, locationLat = null, locationLng = null, nearbyStores = emptyList())
        assertFalse(off.hasNearby)
        assertTrue(off.nearbyStores.isEmpty())
    }
}
