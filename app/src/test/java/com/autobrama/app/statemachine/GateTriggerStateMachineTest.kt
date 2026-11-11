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
    fun firstAccurateFixInside20mTriggersExactlyOnce() {
        val machine = GateTriggerStateMachine(hysteresisOffsetMeters = 150f)
        // Reported production case: 15 m from gate, 20 m activation radius,
        // no earlier Telecom request and no physical departure.
        val first = machine.processDistance(15f, 20f)
        assertTrue("First fix inside the zone must trigger a call", first is StateMachineAction.TriggerCall)
        assertEquals(GateZoneState.IN_ZONE_TRIGGERED, machine.currentState)
        assertTrue(machine.processDistance(10f, 20f) is StateMachineAction.NoAction)
        assertTrue(machine.processDistance(30f, 20f) is StateMachineAction.NoAction)
        assertTrue(machine.processDistance(15f, 20f) is StateMachineAction.NoAction)
    }

    @Test
    fun persistedLatchSuppressesCallAfterServiceRestartUntil170mExit() {
        val restored = GateTriggerStateMachine(hysteresisOffsetMeters = 150f)
        restored.restoreTriggeredState()
        assertTrue(restored.processDistance(15f, 20f) is StateMachineAction.NoAction)
        assertTrue(restored.processDistance(80f, 20f) is StateMachineAction.NoAction)
        assertTrue(restored.processDistance(15f, 20f) is StateMachineAction.NoAction)
        assertTrue(restored.processDistance(171f, 20f) is StateMachineAction.NoAction)
        assertEquals(GateZoneState.OUTSIDE_ZONE, restored.currentState)
        assertTrue(restored.processDistance(15f, 20f) is StateMachineAction.TriggerCall)
    }

    @Test
    fun failedDialCanBeRetriedInSameZone() {
        val machine = GateTriggerStateMachine()
        assertTrue(machine.processDistance(15f, 20f) is StateMachineAction.TriggerCall)
        machine.rearmAfterFailedCall()
        assertTrue(machine.processDistance(15f, 20f) is StateMachineAction.TriggerCall)
    }

    @Test
    fun customHysteresisControlsRearmDistance() {
        // 20 m activation radius + 30 m hysteresis = rearm after 50 m.
        val machine = GateTriggerStateMachine(hysteresisOffsetMeters = 30f)
        assertTrue(machine.processDistance(200f, 20f) is StateMachineAction.NoAction)
        assertTrue(machine.processDistance(15f, 20f) is StateMachineAction.TriggerCall)
        assertTrue(machine.processDistance(49f, 20f) is StateMachineAction.NoAction)
        assertEquals(GateZoneState.HYSTERESIS_COOLDOWN, machine.currentState)
        assertTrue(machine.processDistance(15f, 20f) is StateMachineAction.NoAction)
        assertTrue(machine.processDistance(50f, 20f) is StateMachineAction.NoAction)
        assertEquals(GateZoneState.OUTSIDE_ZONE, machine.currentState)
        assertTrue(machine.processDistance(15f, 20f) is StateMachineAction.TriggerCall)
    }

    @Test
    fun editingHysteresisDoesNotResetExistingCallLatch() {
        val machine = GateTriggerStateMachine(hysteresisOffsetMeters = 150f)
        assertTrue(machine.processDistance(15f, 20f) is StateMachineAction.TriggerCall)
        machine.setHysteresisMeters(25f)
        assertTrue(machine.processDistance(30f, 20f) is StateMachineAction.NoAction)
        assertTrue(machine.processDistance(15f, 20f) is StateMachineAction.NoAction)
        assertTrue(machine.processDistance(45f, 20f) is StateMachineAction.NoAction)
        assertEquals(GateZoneState.OUTSIDE_ZONE, machine.currentState)
        assertTrue(machine.processDistance(15f, 20f) is StateMachineAction.TriggerCall)
    }

    @Test
    fun independentGatesCanHaveDifferentHysteresis() {
        val a = GateTriggerStateMachine(hysteresisOffsetMeters = 25f)
        val b = GateTriggerStateMachine(hysteresisOffsetMeters = 150f)
        assertTrue(a.processDistance(15f, 20f) is StateMachineAction.TriggerCall)
        assertTrue(b.processDistance(15f, 20f) is StateMachineAction.TriggerCall)
        a.processDistance(60f, 20f)
        b.processDistance(60f, 20f)
        assertEquals(GateZoneState.OUTSIDE_ZONE, a.currentState)
        assertEquals(GateZoneState.HYSTERESIS_COOLDOWN, b.currentState)
    }

}
