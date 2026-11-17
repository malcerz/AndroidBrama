package com.autobrama.app.service

/**
 * Monitoring authorization and location source are separate decisions.
 * A detected radar authorizes cycling mode. On the bike the phone usually sits
 * in a pocket, so a satellite-only fix is not required there; instead calls are
 * held back for a grace period after the radar appears (see [RadarGrace]).
 */
internal data class TrackingDecision(
    val allowed: Boolean,
    val satelliteOnly: Boolean,
    val cycling: Boolean = false
)

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
            // "Czekaj na GPS" applies to the car; cycling uses fused location.
            satelliteOnly = waitForGpsFix && !cycling,
            cycling = cycling
        )
    }
}

/**
 * Grace period after the radar is detected: turning the radar on in the garage
 * must not open the gate the rider is standing next to. During the grace period
 * a gate whose zone is entered is latched as "already handled", so it re-arms
 * only after leaving radius + hysteresis, exactly like after a real call.
 *
 * A short loss of the radar signal during a ride does not start a new grace
 * period (that would block the gate on the way home); only an absence longer
 * than [newSessionAfterMillis] does. Times are monotonic (elapsedRealtime).
 */
internal class RadarGrace(
    private val graceMillis: Long = DEFAULT_GRACE_MILLIS,
    private val newSessionAfterMillis: Long = DEFAULT_GRACE_MILLIS
) {
    companion object {
        const val DEFAULT_GRACE_MILLIS = 10 * 60_000L
    }

    private var sessionStart = -1L
    private var lastPresent = -1L
    private var present = false

    fun update(radarPresent: Boolean, now: Long) {
        if (radarPresent) {
            val absentTooLong = lastPresent < 0 || now < lastPresent ||
                (!present && now - lastPresent > newSessionAfterMillis)
            if (sessionStart < 0 || absentTooLong) sessionStart = now
            lastPresent = now
        } else if (present) {
            lastPresent = now
        }
        present = radarPresent
    }

    /** Milliseconds of grace left; 0 when gate calls are allowed. */
    fun remaining(now: Long): Long {
        if (sessionStart < 0 || now < sessionStart) return graceMillis
        return (graceMillis - (now - sessionStart)).coerceAtLeast(0L)
    }
}
