package com.autobrama.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingPolicyTest {
    @Test fun radarUsesFusedLocationEvenWhenCheckboxIsOn() {
        val policy = TrackingPolicy.evaluate(true, true, false, true, true)
        assertTrue(policy.allowed)
        assertFalse(policy.satelliteOnly)
        assertTrue(policy.cycling)
    }

    @Test fun androidAutoWaitForGpsUsesSatelliteProvider() {
        val policy = TrackingPolicy.evaluate(true, true, true, false, false)
        assertTrue(policy.allowed)
        assertTrue(policy.satelliteOnly)
        assertFalse(policy.cycling)
    }

    @Test fun androidAutoWithoutCheckboxUsesExistingFusedLocation() {
        val policy = TrackingPolicy.evaluate(true, false, true, false, false)
        assertTrue(policy.allowed)
        assertFalse(policy.satelliteOnly)
    }

    @Test fun androidAutoWinsOverRadar() {
        val policy = TrackingPolicy.evaluate(true, true, true, true, true)
        assertFalse(policy.cycling)
        assertTrue(policy.satelliteOnly)
    }

    @Test fun radarAloneDoesNotActivateUntilDetected() {
        val policy = TrackingPolicy.evaluate(true, true, false, true, false)
        assertFalse(policy.allowed)
    }

    @Test fun standaloneModeRemainsPossible() {
        val policy = TrackingPolicy.evaluate(false, true, false, false, false)
        assertTrue(policy.allowed)
        assertTrue(policy.satelliteOnly)
    }
}

class RadarGraceTest {
    private val min = 60_000L

    @Test fun graceRunsTenMinutesFromDetection() {
        val g = RadarGrace()
        g.update(true, 1_000L)
        assertEquals(10 * min, g.remaining(1_000L))
        assertTrue(g.remaining(1_000L + 9 * min) > 0)
        assertEquals(0L, g.remaining(1_000L + 10 * min))
    }

    @Test fun beforeAnyDetectionCallsAreHeldBack() {
        assertEquals(10 * min, RadarGrace().remaining(5_000L))
    }

    @Test fun shortSignalLossDoesNotRestartGrace() {
        val g = RadarGrace()
        g.update(true, 0L)
        g.update(false, 30 * min)
        g.update(true, 32 * min)
        assertEquals(0L, g.remaining(32 * min))
    }

    @Test fun longAbsenceStartsNewSession() {
        val g = RadarGrace()
        g.update(true, 0L)
        g.update(false, 30 * min)
        g.update(true, 45 * min)
        assertEquals(10 * min, g.remaining(45 * min))
    }

    @Test fun repeatedPresentUpdatesKeepSession() {
        val g = RadarGrace()
        g.update(true, 0L)
        g.update(true, 20 * min)
        assertEquals(0L, g.remaining(20 * min))
    }
}
