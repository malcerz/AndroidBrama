package com.autobrama.app.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarPresenceTrackerTest {
    @Test fun twoAdvertisementsAreRequired() {
        val tracker = RadarPresenceTracker()
        tracker.seen(1000)
        assertFalse(tracker.isNearby(1000))
        tracker.seen(3000)
        assertTrue(tracker.isNearby(3000))
    }

    @Test fun burstOfAdvertisementsCountsAsOneSighting() {
        val tracker = RadarPresenceTracker()
        tracker.seen(1000)
        tracker.seen(1100)
        tracker.seen(2999)
        assertFalse(tracker.isNearby(2999))
        tracker.seen(3000)
        assertTrue(tracker.isNearby(3000))
    }

    @Test fun temporaryMissingAdvertisementsKeepRadarPresent() {
        val tracker = RadarPresenceTracker()
        tracker.seen(1000)
        tracker.seen(3000)
        assertTrue(tracker.isNearby(43_000))
        assertFalse(tracker.isNearby(43_001))
    }

    @Test fun confirmedRadarSurvivesGapsShorterThanHold() {
        val tracker = RadarPresenceTracker()
        tracker.seen(1000)
        tracker.seen(3000)
        // A single sighting after a long (but still valid) gap keeps presence.
        tracker.seen(38_000)
        assertTrue(tracker.isNearby(38_000))
        assertTrue(tracker.isNearby(78_000))
        assertFalse(tracker.isNearby(78_001))
    }

    @Test fun radarLostLongerThanHoldRequiresNewConfirmation() {
        val tracker = RadarPresenceTracker()
        tracker.seen(1000)
        tracker.seen(3000)
        tracker.seen(50_000)
        assertFalse(tracker.isNearby(50_000))
        tracker.seen(52_000)
        assertTrue(tracker.isNearby(52_000))
    }

    @Test fun sparseAdvertisementsRequireNewConfirmation() {
        val tracker = RadarPresenceTracker()
        tracker.seen(1000)
        tracker.seen(22_000)
        assertFalse(tracker.isNearby(22_000))
        tracker.seen(24_000)
        assertTrue(tracker.isNearby(24_000))
    }

    @Test fun resetDoesNotRetainProximity() {
        val tracker = RadarPresenceTracker()
        tracker.seen(1000)
        tracker.seen(3000)
        tracker.reset()
        assertFalse(tracker.isNearby(3000))
        tracker.seen(5000)
        assertFalse(tracker.isNearby(5000))
    }
}
