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
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "autobrama_settings")

class PreferencesManager(private val context: Context) {

    companion object {
        val KEY_AUTOMATION_ENABLED = booleanPreferencesKey("automation_enabled")
        val KEY_REQUIRE_ANDROID_AUTO = booleanPreferencesKey("require_android_auto")
        val KEY_LAST_CALL_TIMESTAMP = longPreferencesKey("last_call_timestamp")
        val KEY_SLOTS_JSON = stringPreferencesKey("gate_location_slots_json")

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

    val lastCallTimestampFlow: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[KEY_LAST_CALL_TIMESTAMP] ?: 0L
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

    suspend fun setSlots(slots: List<GateLocationSlot>) {
        context.dataStore.edit { preferences ->
            preferences[KEY_SLOTS_JSON] = GateLocationSlot.listToJson(slots)
        }
    }
}
