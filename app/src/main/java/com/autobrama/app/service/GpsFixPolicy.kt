package com.autobrama.app.service

/** A new satellite fix is mandatory for *automatic* gate calls.
 * Network/fused locations and saved coordinates must never unlock a gate.
 */
internal object GpsFixPolicy {
    const val PROVIDER = "gps"
    const val MAX_AGE_NANOS = 8_000_000_000L

    fun isCurrentSatelliteFix(
        provider: String?,
        fixTimeNanos: Long,
        sessionStartedNanos: Long,
        nowNanos: Long,
        hasAccuracy: Boolean
    ): Boolean =
        provider == PROVIDER &&
            hasAccuracy &&
            sessionStartedNanos > 0L &&
            fixTimeNanos >= sessionStartedNanos &&
            nowNanos >= fixTimeNanos &&
            nowNanos - fixTimeNanos <= MAX_AGE_NANOS
}
