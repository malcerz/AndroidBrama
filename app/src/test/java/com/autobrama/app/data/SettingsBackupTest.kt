package com.autobrama.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupTest {
    private fun sample() = SettingsBackup(
        automationEnabled = true,
        requireAndroidAuto = true,
        slots = listOf(
            GateLocationSlot(1, "Dom", true, "+48111222333", 54.3520, 18.6466, 80f),
            GateLocationSlot(2, "Praca", false, "", 54.4, 18.65, 120f),
            GateLocationSlot(3, "Dzialka", true, "12345", 53.1, 19.5, 30f)
        )
    )

    @Test fun backupRoundTrip() {
        val restored = SettingsBackup.parse(sample().toJson())
        assertEquals(3, restored.slots.size)
        assertTrue(restored.automationEnabled)
        assertTrue(restored.requireAndroidAuto)
        assertEquals("+48111222333", restored.slots.first().phoneNumber)
        assertEquals(80f, restored.slots.first().radiusMeters, 0.01f)
        assertFalse(restored.slots[1].enabled)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectInvalidCoordinates() {
        val bad = sample().copy(slots = sample().slots.map {
            if (it.id == 1) it.copy(latitude = 200.0) else it
        })
        SettingsBackup.parse(bad.toJson())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectBadPhoneUri() {
        val bad = sample().copy(slots = sample().slots.map {
            if (it.id == 1) it.copy(phoneNumber = "123;456") else it
        })
        SettingsBackup.parse(bad.toJson())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectDuplicateSlots() {
        val bad = sample().copy(slots = listOf(
            sample().slots[0], sample().slots[0], sample().slots[2]
        ))
        SettingsBackup.parse(bad.toJson())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectUnsupportedVersion() {
        SettingsBackup.parse(sample().toJson().replace("\"version\": 1", "\"version\": 99"))
    }
}
