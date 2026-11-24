package com.autobrama.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DepartureEvidenceTest {
    @Test fun staleLatchCanBeClearedAfterTwoReliableOutsideReadings() {
        val evidence = DepartureEvidence()
        assertFalse(evidence.observe(1, 1100f, 100f, 100f, 1000L))
        assertTrue(evidence.observe(1, 1110f, 100f, 100f, 17_000L))
    }

    @Test fun neverUnlockInGarageEvenWhenEstimateIsFarButUncertain() {
        val evidence = DepartureEvidence()
        assertFalse(evidence.observe(1, 200f, 150f, 100f, 0L))
        assertFalse(evidence.observe(1, 200f, 150f, 100f, 60_000L))
        assertFalse(evidence.observe(1, 13f, 5f, 100f, 80_000L))
    }

    @Test fun oneGpsJumpIsNotEnough() {
        val evidence = DepartureEvidence()
        assertFalse(evidence.observe(1, 550f, 20f, 100f, 0L))
        assertFalse(evidence.observe(1, 13f, 5f, 100f, 1000L))
        assertFalse(evidence.observe(1, 550f, 20f, 100f, 20_000L))
        assertTrue(evidence.observe(1, 550f, 20f, 100f, 37_000L))
    }

    @Test fun confirmationsMustBeSeparatedInTime() {
        val evidence = DepartureEvidence()
        assertFalse(evidence.observe(1, 500f, 30f, 100f, 100L))
        assertFalse(evidence.observe(1, 500f, 30f, 100f, 101L))
        assertTrue(evidence.observe(1, 500f, 30f, 100f, 15_100L))
    }

    @Test fun eachGateIsIndependent() {
        val evidence = DepartureEvidence()
        assertFalse(evidence.observe(1, 500f, 30f, 100f, 0L))
        assertFalse(evidence.observe(2, 500f, 30f, 100f, 10_000L))
        assertTrue(evidence.observe(1, 500f, 30f, 100f, 16_000L))
        assertFalse(evidence.observe(2, 500f, 30f, 100f, 16_000L))
    }

    @Test fun neverAcceptOverlyImpreciseCoordinates() {
        val evidence = DepartureEvidence()
        assertFalse(evidence.observe(1, 5000f, 500f, 100f, 0L))
        assertFalse(evidence.observe(1, 5000f, 500f, 100f, 30_000L))
    }
}
