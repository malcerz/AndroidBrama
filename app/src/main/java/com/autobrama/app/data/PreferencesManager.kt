package com.autobrama.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "autobrama_settings")

data class RadarConfig(val enabled: Boolean, val address: String, val name: String)

class PreferencesManager(private val context: Context) {

    companion object {
        val KEY_AUTOMATION_ENABLED = booleanPreferencesKey("automation_enabled")
        val KEY_REQUIRE_ANDROID_AUTO = booleanPreferencesKey("require_android_auto")
        val KEY_WAIT_FOR_GPS_FIX = booleanPreferencesKey("wait_for_gps_fix")
        val KEY_LAST_CALL_TIMESTAMP = longPreferencesKey("last_call_timestamp")
        val KEY_SLOTS_JSON = stringPreferencesKey("gate_location_slots_json")
        val KEY_RADAR_ENABLED = booleanPreferencesKey("radar_ble_enabled")
        val KEY_RADAR_ADDRESS = stringPreferencesKey("radar_ble_address")
        val KEY_RADAR_NAME = stringPreferencesKey("radar_ble_name")
        private val KEY_LATCHED_GATES = stringSetPreferencesKey("gate_call_latched_slot_ids")

        // Klucze legacy dla wstecznej kompatybilności i migracji
        private val KEY_LEGACY_PHONE = stringPreferencesKey("gate_phone_number")
        private val KEY_LEGACY_LAT = doublePreferencesKey("gate_latitude")
        private val KEY_LEGACY_LON = doublePreferencesKey("gate_longitude")
        private val KEY_LEGACY_RADIUS = floatPreferencesKey("gate_radius_meters")
    }

    val isAutomationEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_AUTOMATION_ENABLED] ?: false
    }

    val requireAndroidAutoFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_REQUIRE_ANDROID_AUTO] ?: true
    }

    /** Safe default: wait for a live satellite fix before an automatic call. */
    val waitForGpsFixFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_WAIT_FOR_GPS_FIX] ?: true
    }

    val lastCallTimestampFlow: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[KEY_LAST_CALL_TIMESTAMP] ?: 0L
    }

    val radarConfigFlow: Flow<RadarConfig> = context.dataStore.data.map { prefs ->
        RadarConfig(
            prefs[KEY_RADAR_ENABLED] ?: false,
            prefs[KEY_RADAR_ADDRESS] ?: "",
            prefs[KEY_RADAR_NAME] ?: ""
        )
    }

    suspend fun setRadarConfig(enabled: Boolean, address: String, name: String) {
        val normalized = address.trim().uppercase()
        require(normalized.isEmpty() ||
            normalized.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}"))) {
            "Nieprawidłowy adres Bluetooth."
        }
        context.dataStore.edit { prefs ->
            prefs[KEY_RADAR_ENABLED] = enabled && normalized.isNotBlank()
            prefs[KEY_RADAR_ADDRESS] = normalized
            prefs[KEY_RADAR_NAME] = name.take(80)
        }
    }

    val slotsFlow: Flow<List<GateLocationSlot>> = context.dataStore.data.map { preferences ->
        val jsonStr = preferences[KEY_SLOTS_JSON]
        if (jsonStr != null) {
            GateLocationSlot.listFromJson(jsonStr)
        } else {
            // Migracja ze starych kluczy pojedynczego slotu
            val legacyPhone = preferences[KEY_LEGACY_PHONE]
            val legacyLat = preferences[KEY_LEGACY_LAT]
            val legacyLon = preferences[KEY_LEGACY_LON]
            val legacyRadius = preferences[KEY_LEGACY_RADIUS]

            val defaultList = GateLocationSlot.defaultSlots().toMutableList()
            if (legacyPhone != null || legacyLat != null) {
                defaultList[0] = defaultList[0].copy(
                    phoneNumber = legacyPhone ?: defaultList[0].phoneNumber,
                    latitude = legacyLat ?: defaultList[0].latitude,
                    longitude = legacyLon ?: defaultList[0].longitude,
                    radiusMeters = legacyRadius ?: defaultList[0].radiusMeters
                )
            }
            defaultList
        }
    }

    suspend fun getSlots(): List<GateLocationSlot> {
        return slotsFlow.first()
    }

    suspend fun isAutomationEnabled(): Boolean {
        return context.dataStore.data.map { it[KEY_AUTOMATION_ENABLED] ?: false }.first()
    }

    suspend fun isAndroidAutoRequired(): Boolean {
        return context.dataStore.data.map { it[KEY_REQUIRE_ANDROID_AUTO] ?: true }.first()
    }

    suspend fun isWaitForGpsFixEnabled(): Boolean = waitForGpsFixFlow.first()

    suspend fun setWaitForGpsFixEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_WAIT_FOR_GPS_FIX] = enabled
        }
    }

    suspend fun setAndroidAutoRequired(required: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_REQUIRE_ANDROID_AUTO] = required
        }
    }

    suspend fun setAutomationEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_AUTOMATION_ENABLED] = enabled
        }
    }

    /** Gate IDs whose call has been initiated; survive app/service restarts. */
    suspend fun getLatchedGateIds(): Set<Int> =
        context.dataStore.data.map { preferences ->
            (preferences[KEY_LATCHED_GATES] ?: emptySet())
                .mapNotNull { it.toIntOrNull() }
                .filter { it in 1..3 }
                .toSet()
        }.first()

    suspend fun setLatchedGateIds(ids: Set<Int>) {
        require(ids.all { it in 1..3 }) { "Invalid gate ID" }
        context.dataStore.edit { preferences ->
            preferences[KEY_LATCHED_GATES] = ids.map { it.toString() }.toSet()
        }
    }

    suspend fun setLastCallTimestamp(timestamp: Long) {
        context.dataStore.edit { preferences ->
            preferences[KEY_LAST_CALL_TIMESTAMP] = timestamp
        }
    }

    suspend fun updateSlot(updated: GateLocationSlot) {
        val currentSlots = getSlots().toMutableList()
        val index = currentSlots.indexOfFirst { it.id == updated.id }
        if (index != -1) {
            currentSlots[index] = updated
        } else {
            currentSlots.add(updated)
        }
        setSlots(currentSlots)
    }

    suspend fun exportSettingsJson(): String = SettingsBackup(
        automationEnabled = isAutomationEnabled(),
        requireAndroidAuto = isAndroidAutoRequired(),
        waitForGpsFix = isWaitForGpsFixEnabled(),
        slots = getSlots(),
        radarConfig = radarConfigFlow.first()
    ).toJson()

    /** Atomic replacement of all portable settings after complete validation. */
    suspend fun importSettings(backup: SettingsBackup) {
        // Revalidate the in-memory backup as well as files loaded from storage.
        val safe = SettingsBackup.parse(backup.toJson())
        context.dataStore.edit { preferences ->
            preferences[KEY_SLOTS_JSON] = GateLocationSlot.listToJson(safe.slots)
            preferences[KEY_AUTOMATION_ENABLED] = safe.automationEnabled
            preferences[KEY_REQUIRE_ANDROID_AUTO] = safe.requireAndroidAuto
            preferences[KEY_WAIT_FOR_GPS_FIX] = safe.waitForGpsFix
            preferences[KEY_LAST_CALL_TIMESTAMP] = 0L
            preferences[KEY_LATCHED_GATES] = emptySet()
            preferences[KEY_RADAR_ENABLED] = safe.radarConfig.enabled
            preferences[KEY_RADAR_ADDRESS] = safe.radarConfig.address
            preferences[KEY_RADAR_NAME] = safe.radarConfig.name
        }
    }

    suspend fun setSlots(slots: List<GateLocationSlot>) {
        context.dataStore.edit { preferences ->
            preferences[KEY_SLOTS_JSON] = GateLocationSlot.listToJson(slots)
        }
    }
}
