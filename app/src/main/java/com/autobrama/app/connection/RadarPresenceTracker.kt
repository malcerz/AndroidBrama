package com.autobrama.app.connection

/**
 * BLE advertisement sightings are evidence of proximity, not of cycling, pairing
 * or an active Bluetooth connection. Two independent sightings are required:
 * advertisements closer together than [minHitSpacingMillis] (for example an
 * advertisement and its scan response) count as a single sighting.
 *
 * Acquisition is fast, release is slow: a low-power scan listens only for a
 * fraction of each interval, so a few missed windows must not drop a radar
 * that is still nearby. Do not accept a radar signal that disappeared long ago
 * as a live ride signal. All times are monotonic (SystemClock.elapsedRealtime()).
 */
class RadarPresenceTracker(
    private val holdMillis: Long = DEFAULT_HOLD_MILLIS,
    private val confirmationWindowMillis: Long = 20_000L,
    private val minHitSpacingMillis: Long = 2_000L
) {
    companion object {
        const val DEFAULT_HOLD_MILLIS = 40_000L
    }

    private var lastSeen: Long = -1L
    private var lastCountedHit: Long = -1L
    private var hits: Int = 0
    private var confirmed = false

    fun seen(now: Long) {
        // An unconfirmed candidate must be confirmed quickly; a confirmed radar
        // stays valid for the whole hold time between sightings.
        val allowedGap = if (confirmed) holdMillis else confirmationWindowMillis
        if (lastSeen < 0 || now < lastSeen || now - lastSeen > allowedGap) {
            hits = 0
            confirmed = false
            lastCountedHit = -1L
        }
        if (lastCountedHit < 0 || now - lastCountedHit >= minHitSpacingMillis) {
            hits++
            lastCountedHit = now
        }
        lastSeen = now
        if (hits >= 2) confirmed = true
    }

    fun isNearby(now: Long): Boolean =
        confirmed && lastSeen >= 0 && now >= lastSeen && now - lastSeen <= holdMillis

    fun reset() {
        lastSeen = -1L
        lastCountedHit = -1L
        hits = 0
        confirmed = false
    }
}
