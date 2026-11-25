package com.autobrama.app.service

/** Monitoring is permitted only by Android Auto or the chosen BLE device.
 * With both switches off, manual test mode allows monitoring.
 * The separate satellite GPS fix gate is enforced by AutoCallForegroundService.
 */
internal data class TrackingDecision(val allowed: Boolean)

internal object TrackingPolicy {
    fun evaluate(
        requireAndroidAuto: Boolean,
        requireBtDevice: Boolean,
        androidAutoConnected: Boolean,
        btDevicePresent: Boolean
    ): TrackingDecision = TrackingDecision(
        allowed = (!requireAndroidAuto && !requireBtDevice) ||
            (requireAndroidAuto && androidAutoConnected) ||
            (requireBtDevice && btDevicePresent)
    )
}
