package com.autobrama.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class SettingsBackupTest {
    private fun sample() = SettingsBackup(
        automationEnabled = true,
        requireAndroidAuto = true,
        radarConfig = RadarConfig(true, "AA:BB:CC:DD:EE:FF", "19813-5"),
        slots = listOf(
            GateLocationSlot(1, "Dom", true, "+48111222333", 54.3520, 18.6466, 80f, 42f),
            GateLocationSlot(2, "Praca", false, "", 54.4, 18.65, 120f),
            GateLocationSlot(3, "Dzialka", true, "12345", 53.1, 19.5, 30f)
        )
    )

    @Test fun backupRoundTrip() {
        val restored = SettingsBackup.parse(sample().toJson())
        assertEquals(3, restored.slots.size)
        assertTrue(restored.automationEnabled)
        assertTrue(restored.requireAndroidAuto)
        assertTrue(restored.waitForGpsFix)
        assertEquals("+48111222333", restored.slots.first().phoneNumber)
        assertEquals(80f, restored.slots.first().radiusMeters, 0.01f)
        assertEquals(42f, restored.slots.first().hysteresisMeters, 0.01f)
        assertFalse(restored.slots[1].enabled)
        assertTrue(restored.radarConfig.enabled)
        assertEquals("AA:BB:CC:DD:EE:FF", restored.radarConfig.address)
        assertEquals("19813-5", restored.radarConfig.name)
    }

    @Test fun gpsCheckboxRoundTripCanBeDisabled() {
        val restored = SettingsBackup.parse(sample().copy(waitForGpsFix = false).toJson())
        assertFalse(restored.waitForGpsFix)
    }

    @Test fun v2BackupWithoutGpsFlagDefaultsToSafeTrue() {
        val old = JSONObject(sample().toJson())
        old.put("version", 2)
        old.remove("waitForGpsFix")
        assertTrue(SettingsBackup.parse(old.toString()).waitForGpsFix)
    }

    @Test
    fun oldBackupWithoutHysteresisKeepsDefault150m() {
        val old = JSONObject(sample().toJson())
        old.put("version", 1)
        old.remove("radarEnabled")
        old.remove("radarAddress")
        old.remove("radarName")
        val slots = old.getJSONArray("slots")
        for (i in 0 until slots.length()) {
            slots.getJSONObject(i).remove("hysteresisMeters")
        }
        val restored = SettingsBackup.parse(old.toString())
        assertEquals(150f, restored.slots.first().hysteresisMeters, 0.01f)
        assertFalse(restored.radarConfig.enabled)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectHysteresisOutsideAllowedRange() {
        val bad = sample().copy(slots = sample().slots.map {
            if (it.id == 1) it.copy(hysteresisMeters = 2500f) else it
        })
        SettingsBackup.parse(bad.toJson())
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
    fun rejectRadarEnabledWithoutIdentity() {
        SettingsBackup.parse(sample().copy(
            radarConfig = RadarConfig(true, "", "19813-5")
        ).toJson())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectUnsupportedVersion() {
        SettingsBackup.parse(JSONObject(sample().toJson()).put("version", 99).toString())
    }
}
