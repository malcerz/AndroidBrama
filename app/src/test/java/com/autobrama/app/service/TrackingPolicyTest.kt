package com.autobrama.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingPolicyTest {
    @Test fun radarRequiresSatelliteFixEvenWhenCheckboxIsOff() {
        val policy = TrackingPolicy.evaluate(true, false, false, true, true)
        assertTrue(policy.allowed)
        assertTrue(policy.satelliteOnly)
    }

    @Test fun androidAutoWaitForGpsUsesSatelliteProvider() {
        val policy = TrackingPolicy.evaluate(true, true, true, false, false)
        assertTrue(policy.allowed)
        assertTrue(policy.satelliteOnly)
    }

    @Test fun androidAutoWithoutCheckboxUsesExistingFusedLocation() {
        val policy = TrackingPolicy.evaluate(true, false, true, false, false)
        assertTrue(policy.allowed)
        assertFalse(policy.satelliteOnly)
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
