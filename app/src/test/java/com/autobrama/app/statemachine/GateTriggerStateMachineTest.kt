package com.autobrama.app.statemachine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GateTriggerStateMachineTest {

    private lateinit var stateMachine: GateTriggerStateMachine
    private val radius = 50.0f
    private val hysteresisOffset = 150.0f
    // hysteresis threshold = 50 + 150 = 200m

    @Before
    fun setUp() {
        stateMachine = GateTriggerStateMachine(hysteresisOffsetMeters = hysteresisOffset)
    }

    @Test
    fun testInitialStateIsOutsideZone() {
        assertEquals(GateZoneState.OUTSIDE_ZONE, stateMachine.currentState)
        assertFalse(stateMachine.hasTriggeredInSession)
    }

    @Test
    fun testTriggerCallOnZoneEntry() {
        // Vehicle is at 300m
        val action1 = stateMachine.processDistance(300f, radius)
        assertTrue(action1 is StateMachineAction.NoAction)
        assertEquals(GateZoneState.OUTSIDE_ZONE, stateMachine.currentState)

        // Vehicle enters zone at 45m (<= 50m)
        val action2 = stateMachine.processDistance(45f, radius)
        assertTrue("Powinien nastąpić trigger połączenia", action2 is StateMachineAction.TriggerCall)
        assertEquals(GateZoneState.IN_ZONE_TRIGGERED, stateMachine.currentState)
        assertTrue(stateMachine.hasTriggeredInSession)
    }

    @Test
    fun testNoRepeatedCallWhileStayingInZone() {
        // First entry
        stateMachine.processDistance(40f, radius)
        assertEquals(GateZoneState.IN_ZONE_TRIGGERED, stateMachine.currentState)

        // Vehicle moves to 30m, 20m, 10m inside zone
        val action1 = stateMachine.processDistance(30f, radius)
        assertTrue(action1 is StateMachineAction.NoAction)

        val action2 = stateMachine.processDistance(10f, radius)
        assertTrue(action2 is StateMachineAction.NoAction)
        assertEquals(GateZoneState.IN_ZONE_TRIGGERED, stateMachine.currentState)
    }

    @Test
    fun testHysteresisBufferBlocksCallWhenMovingSlightlyAwayAndReturning() {
        // Enter zone and trigger call
        stateMachine.processDistance(40f, radius)

        // Vehicle drives out of 50m radius to 80m (between 50m and 200m)
        val action1 = stateMachine.processDistance(80f, radius)
        assertTrue(action1 is StateMachineAction.NoAction)
        assertEquals(GateZoneState.HYSTERESIS_COOLDOWN, stateMachine.currentState)

        // Vehicle drives to 180m (still < 200m threshold)
        val action2 = stateMachine.processDistance(180f, radius)
        assertTrue(action2 is StateMachineAction.NoAction)
        assertEquals(GateZoneState.HYSTERESIS_COOLDOWN, stateMachine.currentState)

        // Vehicle reverses back into gate zone at 30m without having reached 200m
        val action3 = stateMachine.processDistance(30f, radius)
        assertTrue("Wjazd z powrotem bez przekroczenia histerezy nie może wykonać ponownego połączenia!", action3 is StateMachineAction.NoAction)
        assertEquals(GateZoneState.IN_ZONE_TRIGGERED, stateMachine.currentState)
    }

    @Test
    fun testExitingBeyondHysteresisThresholdReArmsStateMachine() {
        // Enter zone and trigger call
        stateMachine.processDistance(40f, radius)
        assertEquals(GateZoneState.IN_ZONE_TRIGGERED, stateMachine.currentState)

        // Vehicle drives past hysteresis threshold (201m >= 200m)
        val actionExit = stateMachine.processDistance(201f, radius)
        assertTrue(actionExit is StateMachineAction.NoAction)
        assertEquals(GateZoneState.OUTSIDE_ZONE, stateMachine.currentState)

        // Vehicle returns and enters zone again at 45m
        val actionReEntry = stateMachine.processDistance(45f, radius)
        assertTrue("Po opuszczeniu strefy histerezy ponowny wjazd powinien wywołać połączenie", actionReEntry is StateMachineAction.TriggerCall)
        assertEquals(GateZoneState.IN_ZONE_TRIGGERED, stateMachine.currentState)
    }

    @Test
    fun testResetSessionArmsStateMachineImmediately() {
        // Trigger call
        stateMachine.processDistance(40f, radius)
        assertEquals(GateZoneState.IN_ZONE_TRIGGERED, stateMachine.currentState)

        // Android Auto reconnects -> session reset
        stateMachine.resetSession()
        assertEquals(GateZoneState.OUTSIDE_ZONE, stateMachine.currentState)
        assertFalse(stateMachine.hasTriggeredInSession)
    }
    @Test
    fun startupInsideGateDoesNotDialUntilVehicleLeavesAndReturns() {
        val safe = GateTriggerStateMachine(
            hysteresisOffsetMeters = 150f,
            requireOutsideBeforeFirstTrigger = true
        )
        assertTrue(safe.processDistance(15f, radius) is StateMachineAction.NoAction)
        assertTrue(safe.processDistance(80f, radius) is StateMachineAction.NoAction)
        assertTrue(safe.processDistance(30f, radius) is StateMachineAction.NoAction)
        assertEquals(GateZoneState.HYSTERESIS_COOLDOWN, safe.currentState)
        assertTrue(safe.processDistance(210f, radius) is StateMachineAction.NoAction)
        assertTrue(safe.processDistance(45f, radius) is StateMachineAction.TriggerCall)
    }

    @Test
    fun failedDialCanBeRearmedWithoutAnExtraGeofenceDeparture() {
        val safe = GateTriggerStateMachine(requireOutsideBeforeFirstTrigger = true)
        safe.processDistance(300f, radius)
        assertTrue(safe.processDistance(40f, radius) is StateMachineAction.TriggerCall)
        safe.rearmAfterFailedCall()
        assertTrue(safe.processDistance(40f, radius) is StateMachineAction.TriggerCall)
    }

}
