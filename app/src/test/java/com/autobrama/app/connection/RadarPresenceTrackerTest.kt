package com.autobrama.app.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarPresenceTrackerTest {
    @Test fun twoAdvertisementsAreRequired() {
        val tracker = RadarPresenceTracker()
        tracker.seen(1000)
        assertFalse(tracker.isNearby(1000))
        tracker.seen(1200)
        assertTrue(tracker.isNearby(1200))
    }

    @Test fun temporaryMissingAdvertisementsKeepRadarPresent() {
        val tracker = RadarPresenceTracker()
        tracker.seen(1000)
        tracker.seen(1200)
        assertTrue(tracker.isNearby(60_000))
        assertFalse(tracker.isNearby(61_201))
    }

    @Test fun sparseAdvertisementsRequireNewConfirmation() {
        val tracker = RadarPresenceTracker()
        tracker.seen(1000)
        tracker.seen(17_000)
        assertFalse(tracker.isNearby(17_000))
        tracker.seen(17_250)
        assertTrue(tracker.isNearby(17_250))
    }

    @Test fun resetDoesNotRetainProximity() {
        val tracker = RadarPresenceTracker()
        tracker.seen(1000)
        tracker.seen(1001)
        tracker.reset()
        assertFalse(tracker.isNearby(1001))
        tracker.seen(1002)
        assertFalse(tracker.isNearby(1002))
    }
}
