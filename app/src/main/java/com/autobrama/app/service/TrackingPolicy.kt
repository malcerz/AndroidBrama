package com.autobrama.app.service

/**
 * Whether GPS monitoring may run, and whether the gate calls are held back by
 * the per-gate grace period ([cycling]).
 */
internal data class TrackingDecision(
    val allowed: Boolean,
    val cycling: Boolean = false
)

/**
 * Two independent switches choose what activates monitoring:
 *  - "Wymagaj Android Auto": active while Android Auto projection is connected,
 *  - "Wymagaj urządzenia BT": active while the selected BLE device is nearby.
 * Each enabled source is sufficient on its own. With both switches off,
 * monitoring is always active (test mode without a car or device).
 *
 * The grace period applies only to a start caused by the BLE device: while
 * Android Auto is connected the gate calls are never delayed.
 */
internal object TrackingPolicy {
    fun evaluate(
        requireAndroidAuto: Boolean,
        requireBtDevice: Boolean,
        androidAutoConnected: Boolean,
        btDevicePresent: Boolean
    ): TrackingDecision {
        val androidAutoActive = requireAndroidAuto && androidAutoConnected
        val deviceActive = requireBtDevice && btDevicePresent
        val allowed = (!requireAndroidAuto && !requireBtDevice) ||
            androidAutoActive || deviceActive
        return TrackingDecision(
            allowed = allowed,
            cycling = deviceActive && !androidAutoConnected
        )
    }
}

/**
 * Tracks one "session" of the selected BLE device so a gate can be held back
 * for its own grace period after the device appears: switching the device on in
 * the garage must not open the gate the rider is standing next to. During the
 * grace period a gate whose zone is entered is latched as "already handled",
 * so it re-arms only after leaving radius + hysteresis, exactly like after a
 * real call.
 *
 * A short loss of the signal during a ride does not start a new session (that
 * would block the gate on the way home); only an absence longer than
 * [newSessionAfterMillis] does. Times are monotonic (elapsedRealtime).
 */
internal class RadarGrace(
    private val newSessionAfterMillis: Long = DEFAULT_NEW_SESSION_MILLIS
) {
    companion object {
        const val DEFAULT_NEW_SESSION_MILLIS = 10 * 60_000L
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

    /** Milliseconds of a [graceMillis] period left; 0 when gate calls are allowed. */
    fun remaining(now: Long, graceMillis: Long): Long {
        if (graceMillis <= 0L) return 0L
        if (sessionStart < 0 || now < sessionStart) return graceMillis
        return (graceMillis - (now - sessionStart)).coerceAtLeast(0L)
    }
}
