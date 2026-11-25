package com.autobrama.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsFixPolicyTest {
    private val session = 5_000_000_000L
    private val now = 9_000_000_000L

    @Test fun newSatelliteFixAllowsEvaluatingGate() {
        assertTrue(GpsFixPolicy.isCurrentSatelliteFix("gps", 8_000_000_000L, session, now, true))
    }

    @Test fun wifiCellOrFusedLocationsNeverUnlock() {
        for (source in listOf("network", "fused", "passive", null)) {
            assertFalse(GpsFixPolicy.isCurrentSatelliteFix(source, 8_000_000_000L, session, now, true))
        }
    }

    @Test fun cachedSatelliteFixFromBeforeSessionIsRejected() {
        assertFalse(GpsFixPolicy.isCurrentSatelliteFix("gps", 4_000_000_000L, session, now, true))
    }

    @Test fun staleOrFutureFixIsRejected() {
        assertFalse(GpsFixPolicy.isCurrentSatelliteFix("gps", session, session, 14_000_000_000L, true))
        assertFalse(GpsFixPolicy.isCurrentSatelliteFix("gps", 10_000_000_000L, session, now, true))
    }

    @Test fun gpsWithoutAccuracyIsRejected() {
        assertFalse(GpsFixPolicy.isCurrentSatelliteFix("gps", 8_000_000_000L, session, now, false))
    }

    @Test fun newSessionCannotTrustOldGpsTime() {
        assertFalse(GpsFixPolicy.isCurrentSatelliteFix("gps", 7_000_000_000L, 8_000_000_000L, now, true))
    }
}
