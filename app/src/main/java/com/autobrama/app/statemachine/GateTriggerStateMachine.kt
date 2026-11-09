package com.autobrama.app.statemachine

enum class GateZoneState {
    OUTSIDE_ZONE,
    IN_ZONE_TRIGGERED,
    HYSTERESIS_COOLDOWN
}

sealed class StateMachineAction {
    data class TriggerCall(
        val distance: Float,
        val radiusMeters: Float,
        val reason: String
    ) : StateMachineAction()

    data class NoAction(
        val currentState: GateZoneState,
        val distance: Float,
        val radiusMeters: Float,
        val reason: String
    ) : StateMachineAction()
}

class GateTriggerStateMachine(
    private val hysteresisOffsetMeters: Float = 150.0f
) {
    var currentState: GateZoneState = GateZoneState.OUTSIDE_ZONE
        private set

    var hasTriggeredInSession: Boolean = false
        private set

    var lastTriggerDistance: Float? = null
        private set

    fun resetSession() {
        currentState = GateZoneState.OUTSIDE_ZONE
        hasTriggeredInSession = false
        lastTriggerDistance = null
    }

    /**
     * Processes new GPS distance reading against configured gate radius.
     * Returns StateMachineAction.TriggerCall if call condition is met, or NoAction otherwise.
     */
    @Synchronized
    fun processDistance(distance: Float, radiusMeters: Float): StateMachineAction {
        val hysteresisThreshold = radiusMeters + hysteresisOffsetMeters

        return when (currentState) {
            GateZoneState.OUTSIDE_ZONE -> {
                if (distance <= radiusMeters) {
                    // Wjechanie w strefę bramy!
                    currentState = GateZoneState.IN_ZONE_TRIGGERED
                    hasTriggeredInSession = true
                    lastTriggerDistance = distance
                    StateMachineAction.TriggerCall(
                        distance = distance,
                        radiusMeters = radiusMeters,
                        reason = "Wjazd w strefę bramy (dystans: ${distance.toInt()} m <= ${radiusMeters.toInt()} m)"
                    )
                } else {
                    StateMachineAction.NoAction(
                        currentState = currentState,
                        distance = distance,
                        radiusMeters = radiusMeters,
                        reason = "Pojazd poza strefą (dystans: ${distance.toInt()} m > ${radiusMeters.toInt()} m)"
                    )
                }
            }

            GateZoneState.IN_ZONE_TRIGGERED -> {
                if (distance > radiusMeters) {
                    if (distance >= hysteresisThreshold) {
                        // Pojazd oddalił się poza próg histerezy (radius + 150 m) - zresetuj strefę!
                        currentState = GateZoneState.OUTSIDE_ZONE
                        StateMachineAction.NoAction(
                            currentState = currentState,
                            distance = distance,
                            radiusMeters = radiusMeters,
                            reason = "Wyjazd poza strefę histerezy (${distance.toInt()} m >= ${hysteresisThreshold.toInt()} m) – ponowne uzbrojenie"
                        )
                    } else {
                        // Pojazd wyjechał ze strefy bramy, ale pozostaje w buforze histerezy
                        currentState = GateZoneState.HYSTERESIS_COOLDOWN
                        StateMachineAction.NoAction(
                            currentState = currentState,
                            distance = distance,
                            radiusMeters = radiusMeters,
                            reason = "W buforze histerezy (${distance.toInt()} m < ${hysteresisThreshold.toInt()} m) – blokada połączeń"
                        )
                    }
                } else {
                    // Wciąż w strefie bramy – połączenie już wykonane, blokuj
                    StateMachineAction.NoAction(
                        currentState = currentState,
                        distance = distance,
                        radiusMeters = radiusMeters,
                        reason = "Pojazd wciąż w strefie (połączenie zrealizowane w tej sesji)"
                    )
                }
            }

            GateZoneState.HYSTERESIS_COOLDOWN -> {
                if (distance >= hysteresisThreshold) {
                    // Oddalenie się na wymaganą odległość resetującą (radius + 150m)
                    currentState = GateZoneState.OUTSIDE_ZONE
                    StateMachineAction.NoAction(
                        currentState = currentState,
                        distance = distance,
                        radiusMeters = radiusMeters,
                        reason = "Oddalono się od bramy (${distance.toInt()} m >= ${hysteresisThreshold.toInt()} m) – układ uzbrojony ponownie"
                    )
                } else if (distance <= radiusMeters) {
                    // Powrót w strefę bez uprzedniego oddalenia się poza 150m – blokujemy dzwonienie
                    currentState = GateZoneState.IN_ZONE_TRIGGERED
                    StateMachineAction.NoAction(
                        currentState = currentState,
                        distance = distance,
                        radiusMeters = radiusMeters,
                        reason = "Powrót w strefę bramy z bufora histerezy – połączenie zablokowane (ta sama sesja)"
                    )
                } else {
                    StateMachineAction.NoAction(
                        currentState = currentState,
                        distance = distance,
                        radiusMeters = radiusMeters,
                        reason = "W buforze histerezy (${distance.toInt()} m / ${hysteresisThreshold.toInt()} m)"
                    )
                }
            }
        }
    }
}
