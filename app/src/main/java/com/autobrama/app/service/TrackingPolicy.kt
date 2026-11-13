package com.autobrama.app.service

/**
 * Monitoring authorization and location source are separate decisions.
 * A detected radar may authorize cycling mode, but only a fresh satellite
 * GPS measurement can trigger the call when cycling is the active mode.
 */
internal data class TrackingDecision(val allowed: Boolean, val satelliteOnly: Boolean)

internal object TrackingPolicy {
    fun evaluate(
        requireAndroidAuto: Boolean,
        waitForGpsFix: Boolean,
        androidAutoConnected: Boolean,
        radarEnabled: Boolean,
        radarPresent: Boolean
    ): TrackingDecision {
        val cycling = radarEnabled && radarPresent && !androidAutoConnected
        val allowed = !requireAndroidAuto || androidAutoConnected || cycling
        return TrackingDecision(
            allowed = allowed,
            satelliteOnly = waitForGpsFix || cycling
        )
    }
}
