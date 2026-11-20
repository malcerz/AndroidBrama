package com.autobrama.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingPolicyTest {
    // evaluate(requireAndroidAuto, requireBtDevice, androidAutoConnected, btDevicePresent)

    @Test fun detectedDeviceActivatesAndStartsGraceWithoutAndroidAuto() {
        val policy = TrackingPolicy.evaluate(true, true, false, true)
        assertTrue(policy.allowed)
        assertTrue(policy.cycling)
    }

    @Test fun androidAutoActivatesIndependentlyOfDevice() {
        val policy = TrackingPolicy.evaluate(true, true, true, false)
        assertTrue(policy.allowed)
        assertFalse(policy.cycling)
    }

    @Test fun androidAutoOnlyIgnoresDevice() {
        val policy = TrackingPolicy.evaluate(true, false, false, true)
        assertFalse(policy.allowed)
    }

    @Test fun deviceOnlyIgnoresAndroidAuto() {
        assertFalse(TrackingPolicy.evaluate(false, true, true, false).allowed)
        val policy = TrackingPolicy.evaluate(false, true, false, true)
        assertTrue(policy.allowed)
        assertTrue(policy.cycling)
    }

    @Test fun androidAutoWinsOverDeviceSoThereIsNoGrace() {
        val policy = TrackingPolicy.evaluate(true, true, true, true)
        assertTrue(policy.allowed)
        assertFalse(policy.cycling)
    }

    @Test fun deviceAloneDoesNotActivateUntilDetected() {
        assertFalse(TrackingPolicy.evaluate(true, true, false, false).allowed)
        assertFalse(TrackingPolicy.evaluate(false, true, false, false).allowed)
    }

    @Test fun bothSwitchesOffMeansAlwaysActiveWithoutGrace() {
        val policy = TrackingPolicy.evaluate(false, false, false, false)
        assertTrue(policy.allowed)
        assertFalse(policy.cycling)
    }
}

class RadarGraceTest {
    private val min = 60_000L
    private val grace = 10 * min

    @Test fun graceRunsTenMinutesFromDetection() {
        val g = RadarGrace()
        g.update(true, 1_000L)
        assertEquals(10 * min, g.remaining(1_000L, grace))
        assertTrue(g.remaining(1_000L + 9 * min, grace) > 0)
        assertEquals(0L, g.remaining(1_000L + 10 * min, grace))
    }

    @Test fun beforeAnyDetectionCallsAreHeldBack() {
        assertEquals(10 * min, RadarGrace().remaining(5_000L, grace))
    }

    @Test fun shortSignalLossDoesNotRestartGrace() {
        val g = RadarGrace()
        g.update(true, 0L)
        g.update(false, 30 * min)
        g.update(true, 32 * min)
        assertEquals(0L, g.remaining(32 * min, grace))
    }

    @Test fun longAbsenceStartsNewSession() {
        val g = RadarGrace()
        g.update(true, 0L)
        g.update(false, 30 * min)
        g.update(true, 45 * min)
        assertEquals(10 * min, g.remaining(45 * min, grace))
    }

    @Test fun repeatedPresentUpdatesKeepSession() {
        val g = RadarGrace()
        g.update(true, 0L)
        g.update(true, 20 * min)
        assertEquals(0L, g.remaining(20 * min, grace))
    }

    @Test fun eachGateHasItsOwnGraceFromTheSameSession() {
        val g = RadarGrace()
        g.update(true, 0L)
        assertEquals(0L, g.remaining(0L, 0L))
        assertEquals(5 * min, g.remaining(0L, 5 * min))
        assertEquals(2 * min, g.remaining(3 * min, 5 * min))
        assertEquals(0L, g.remaining(6 * min, 5 * min))
        assertEquals(4 * min, g.remaining(6 * min, 10 * min))
    }
}
