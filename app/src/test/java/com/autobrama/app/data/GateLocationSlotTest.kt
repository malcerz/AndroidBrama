package com.autobrama.app.data

import com.autobrama.app.statemachine.GateTriggerStateMachine
import com.autobrama.app.statemachine.GateZoneState
import com.autobrama.app.statemachine.StateMachineAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GateLocationSlotTest {

    @Test
    fun testDefaultSlotsContainThreeConfiguredSlots() {
        val defaults = GateLocationSlot.defaultSlots()
        assertEquals(3, defaults.size)

        assertEquals(1, defaults[0].id)
        assertFalse(defaults[0].enabled)
        assertEquals("", defaults[0].phoneNumber)

        assertEquals(2, defaults[1].id)
        assertFalse(defaults[1].enabled)

        assertEquals(3, defaults[2].id)
        assertFalse(defaults[2].enabled)
    }

    @Test
    fun testJsonSerializationAndDeserialization() {
        val originalSlots = listOf(
            GateLocationSlot(1, "Dom Główny", true, "+48111222333", 52.1234, 21.4321, 60.0f),
            GateLocationSlot(2, "Biuro Tech", true, "+48444555666", 52.5678, 21.8765, 80.0f),
            GateLocationSlot(3, "Domek Letniskowy", false, "+48777888999", 54.1234, 18.4321, 100.0f)
        )

        val json = GateLocationSlot.listToJson(originalSlots)
        val restored = GateLocationSlot.listFromJson(json)

        assertEquals(3, restored.size)
        assertEquals("Dom Główny", restored[0].name)
        assertEquals("+48111222333", restored[0].phoneNumber)
        assertEquals(52.1234, restored[0].latitude, 0.0001)
        assertEquals(60.0f, restored[0].radiusMeters, 0.01f)
        assertTrue(restored[0].enabled)

        assertEquals("Biuro Tech", restored[1].name)
        assertTrue(restored[1].enabled)

        assertEquals("Domek Letniskowy", restored[2].name)
        assertFalse(restored[2].enabled)
    }

    @Test
    fun testIndependentStateMachinesForMultipleSlots() {
        val slot1 = GateLocationSlot(1, "Dom", true, "+48111", 52.0, 21.0, 50.0f)
        val slot2 = GateLocationSlot(2, "Firma", true, "+48222", 52.1, 21.1, 70.0f)

        val smMap = mutableMapOf<Int, GateTriggerStateMachine>()
        val sm1 = smMap.getOrPut(slot1.id) { GateTriggerStateMachine(150f) }
        val sm2 = smMap.getOrPut(slot2.id) { GateTriggerStateMachine(150f) }

        // Vehicle enters slot 1 (30m <= 50m), but is far from slot 2 (1000m)
        val action1 = sm1.processDistance(30f, slot1.radiusMeters)
        val action2 = sm2.processDistance(1000f, slot2.radiusMeters)

        assertTrue("Slot 1 powinien wyzwolić połączenie", action1 is StateMachineAction.TriggerCall)
        assertTrue("Slot 2 nie powinien wyzwolić połączenia", action2 is StateMachineAction.NoAction)

        assertEquals(GateZoneState.IN_ZONE_TRIGGERED, sm1.currentState)
        assertEquals(GateZoneState.OUTSIDE_ZONE, sm2.currentState)

        // Now vehicle drives to slot 2 (40m <= 70m) and moves away from slot 1 (800m >= 200m)
        val action1Exit = sm1.processDistance(800f, slot1.radiusMeters)
        val action2Enter = sm2.processDistance(40f, slot2.radiusMeters)

        assertEquals(GateZoneState.OUTSIDE_ZONE, sm1.currentState)
        assertTrue("Slot 2 powinien teraz wyzwolić swoje połączenie", action2Enter is StateMachineAction.TriggerCall)
        assertEquals(GateZoneState.IN_ZONE_TRIGGERED, sm2.currentState)
    }
}
