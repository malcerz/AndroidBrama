package com.autobrama.app.service

/**
 * Conservative, per-gate evidence that the phone moved away from a latched
 * gate. This never authorizes a call. Only a later normal GPS/BT or Android
 * Auto trigger may dial a gate.
 *
 * Accuracy is an uncertainty radius: distance minus accuracy must still be
 * beyond the hysteresis threshold plus a safety margin. Two fresh, separated
 * samples avoid rearming on a single spurious Wi-Fi/cell position.
 */
internal class DepartureEvidence(
    private val minConfirmationMs: Long = 15_000L,
    private val maxAccuracyMeters: Float = 250f,
    private val marginMeters: Float = 20f
) {
    private val firstOutsideAt = mutableMapOf<Int, Long>()

    fun observe(
        slotId: Int,
        distanceMeters: Float,
        accuracyMeters: Float,
        rearmThresholdMeters: Float,
        nowMillis: Long
    ): Boolean {
        val safe = distanceMeters.isFinite() &&
            accuracyMeters.isFinite() && accuracyMeters >= 0f &&
            accuracyMeters <= maxAccuracyMeters &&
            rearmThresholdMeters.isFinite() &&
            distanceMeters - accuracyMeters >= rearmThresholdMeters + marginMeters

        if (!safe) {
            firstOutsideAt.remove(slotId)
            return false
        }
        val first = firstOutsideAt[slotId]
        if (first == null || nowMillis < first) {
            firstOutsideAt[slotId] = nowMillis
            return false
        }
        if (nowMillis - first < minConfirmationMs) return false
        firstOutsideAt.remove(slotId)
        return true
    }

    fun reset() = firstOutsideAt.clear()
}
