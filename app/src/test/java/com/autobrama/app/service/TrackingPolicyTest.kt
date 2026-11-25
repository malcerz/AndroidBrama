package com.autobrama.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingPolicyTest {
    @Test fun selectedBluetoothActivatesMonitoring() {
        assertTrue(TrackingPolicy.evaluate(true, true, false, true).allowed)
        assertFalse(TrackingPolicy.evaluate(false, true, false, false).allowed)
    }

    @Test fun androidAutoActivatesIndependently() {
        assertTrue(TrackingPolicy.evaluate(true, true, true, false).allowed)
        assertFalse(TrackingPolicy.evaluate(true, false, false, true).allowed)
    }

    @Test fun eitherEnabledTriggerIsEnough() {
        assertTrue(TrackingPolicy.evaluate(true, true, true, true).allowed)
        assertFalse(TrackingPolicy.evaluate(true, true, false, false).allowed)
    }

    @Test fun noTriggerSelectedPreservesTestMode() {
        assertTrue(TrackingPolicy.evaluate(false, false, false, false).allowed)
    }
}
