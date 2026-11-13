package com.autobrama.app.connection

/**
 * BLE advertisement sightings are evidence of proximity, not of cycling, pairing
 * or an active Bluetooth connection. Two independent sightings are required.
 * Do not accept a radar signal that disappeared long ago as a live ride signal.
 * All times are monotonic (SystemClock.elapsedRealtime()).
 */
class RadarPresenceTracker(
    private val holdMillis: Long = 15_000L,
    private val confirmationWindowMillis: Long = 15_000L
) {
    private var lastSeen: Long = -1L
    private var hits: Int = 0
    private var confirmed = false

    fun seen(now: Long) {
        if (lastSeen < 0 || now < lastSeen || now - lastSeen > confirmationWindowMillis) {
            hits = 0
            confirmed = false
        }
        hits++
        lastSeen = now
        if (hits >= 2) confirmed = true
    }

    fun isNearby(now: Long): Boolean =
        confirmed && lastSeen >= 0 && now >= lastSeen && now - lastSeen <= holdMillis

    fun reset() {
        lastSeen = -1L
        hits = 0
        confirmed = false
    }
}
