package com.autobrama.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GateDistancePolicyTest {
    @Test fun satelliteFixAt4mCanEnter10mZoneEvenIfEstimatedAccuracyIsWorseThan5m() {
        // Location source/freshness is enforced separately by GpsFixPolicy.
        // Old logic incorrectly required accuracy <= radius/2 = 5 m.
        assertTrue(GateDistancePolicy.isInside(4f, 10f))
        assertTrue(GateDistancePolicy.isInside(10f, 10f))
        assertFalse(GateDistancePolicy.isInside(10.1f, 10f))
    }

    @Test fun verySmallUserRadiusCanTriggerWhenMeasuredDistanceQualifies() {
        assertTrue(GateDistancePolicy.isInside(0.5f, 1f))
        assertTrue(GateDistancePolicy.isInside(0f, 0f))
        assertFalse(GateDistancePolicy.isInside(2f, 1f))
    }

    @Test fun nonFiniteOrNegativeDistancesNeverTrigger() {
        assertFalse(GateDistancePolicy.isInside(Float.NaN, 10f))
        assertFalse(GateDistancePolicy.isInside(Float.POSITIVE_INFINITY, 10f))
        assertFalse(GateDistancePolicy.isInside(-1f, 10f))
    }

    @Test fun rearmNeedsClearlyOutsideHysteresisTakingAccuracyIntoAccount() {
        // Radius 10 + hysteresis 80 => threshold 90 m.
        assertFalse(GateDistancePolicy.safelyBeyondHysteresis(95f, 10f, 10f, 80f))
        assertTrue(GateDistancePolicy.safelyBeyondHysteresis(120f, 20f, 10f, 80f))
        assertFalse(GateDistancePolicy.safelyBeyondHysteresis(150f, 120f, 10f, 80f))
    }
}
