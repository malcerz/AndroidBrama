package com.autobrama.app.service

/**
 * Gate entry uses the *measured distance* from a fresh satellite GPS fix.
 * Horizontal accuracy is shown to the user, but is not an extra hidden
 * threshold that prevents a 10 m radius from ever triggering.
 *
 * Rearming after a previous call is intentionally stricter: only an
 * observation definitely outside the hysteresis zone may remove a latch.
 */
internal object GateDistancePolicy {
    fun isInside(distanceMeters: Float, radiusMeters: Float): Boolean =
        distanceMeters.isFinite() && radiusMeters.isFinite() &&
            radiusMeters >= 0f && distanceMeters >= 0f &&
            distanceMeters <= radiusMeters

    fun safelyBeyondHysteresis(
        distanceMeters: Float,
        accuracyMeters: Float,
        radiusMeters: Float,
        hysteresisMeters: Float
    ): Boolean =
        distanceMeters.isFinite() && accuracyMeters.isFinite() &&
            accuracyMeters >= 0f && accuracyMeters <= 100f &&
            radiusMeters.isFinite() && hysteresisMeters.isFinite() &&
            distanceMeters - accuracyMeters >= radiusMeters + hysteresisMeters
}
