package com.autobrama.app.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Portable configuration only. Never exports runtime state or permission grants.
 * Phone numbers and GPS coordinates are sensitive: backup files should be stored privately.
 */
data class SettingsBackup(
    val automationEnabled: Boolean,
    val requireAndroidAuto: Boolean,
    val slots: List<GateLocationSlot>,
    val radarConfig: RadarConfig = RadarConfig(false, "", ""),
    val waitForGpsFix: Boolean = true
) {
    fun toJson(): String = JSONObject().apply {
        put("format", FORMAT)
        put("version", VERSION)
        put("automationEnabled", automationEnabled)
        put("requireAndroidAuto", requireAndroidAuto)
        put("waitForGpsFix", waitForGpsFix)
        put("radarEnabled", radarConfig.enabled)
        put("radarAddress", radarConfig.address)
        put("radarName", radarConfig.name)
        put("slots", JSONArray().apply {
            slots.sortedBy { it.id }.forEach { put(it.toJsonObject()) }
        })
    }.toString(2)

    companion object {
        const val FORMAT = "AndroidBramaSettings"
        const val VERSION = 3
        const val MAX_BACKUP_BYTES = 1024 * 1024

        /** Validates the whole file before any DataStore preference is changed. */
        fun parse(json: String): SettingsBackup {
            require(json.toByteArray(Charsets.UTF_8).size <= MAX_BACKUP_BYTES) {
                "Plik konfiguracji jest zbyt duży."
            }
            try {
                val root = JSONObject(json)
                require(root.getString("format") == FORMAT) {
                    "Nieprawidłowy format pliku ustawień."
                }
                require(root.getInt("version") in 1..VERSION) {
                    "Nieobsługiwana wersja pliku ustawień."
                }

                val records = root.getJSONArray("slots")
                require(records.length() == 3) { "Plik musi zawierać dokładnie 3 bramy." }
                val slots = (0 until records.length()).map { i ->
                    val item = records.getJSONObject(i)
                    val slot = GateLocationSlot(
                        id = item.getInt("id"),
                        name = item.getString("name").trim(),
                        enabled = item.getBoolean("enabled"),
                        phoneNumber = item.getString("phoneNumber").trim(),
                        latitude = item.getDouble("latitude"),
                        longitude = item.getDouble("longitude"),
                        radiusMeters = item.getDouble("radiusMeters").toFloat(),
                        // Older v1 backups have no hysteresis field.
                        hysteresisMeters = if (item.has("hysteresisMeters"))
                            item.getDouble("hysteresisMeters").toFloat() else 150f
                    )
                    require(slot.id in 1..3) { "Nieprawidłowy identyfikator bramy." }
                    require(slot.name.isNotBlank() && slot.name.length <= 100) { "Nieprawidłowa nazwa bramy." }
                    require(slot.latitude.isFinite() && slot.latitude in -90.0..90.0 &&
                            slot.longitude.isFinite() && slot.longitude in -180.0..180.0) {
                        "Nieprawidłowe współrzędne GPS."
                    }
                    require(slot.radiusMeters.isFinite() && slot.radiusMeters in 20f..500f) {
                        "Odległość aktywacji musi wynosić 20–500 m."
                    }
                    require(slot.hysteresisMeters.isFinite() && slot.hysteresisMeters in 0f..2000f) {
                        "Histereza musi wynosić 0–2000 m."
                    }
                    require(slot.phoneNumber.isEmpty() ||
                            slot.phoneNumber.matches(Regex("^\\+?[0-9*# ()-]{1,30}$"))) {
                        "Nieprawidłowy numer telefonu."
                    }
                    slot
                }
                require(slots.map { it.id }.toSet() == setOf(1, 2, 3)) {
                    "W pliku są powtórzone lub brakujące identyfikatory bram."
                }
                val radarEnabled = root.optBoolean("radarEnabled", false)
                val radarAddress = root.optString("radarAddress", "").trim().uppercase()
                val radarName = root.optString("radarName", "").take(80)
                require(radarAddress.isEmpty() ||
                    radarAddress.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}"))) {
                    "Nieprawidłowy adres radaru BLE."
                }
                require(!radarEnabled || radarAddress.isNotBlank()) {
                    "Tryb radaru wymaga wskazania urządzenia."
                }
                return SettingsBackup(
                    automationEnabled = root.getBoolean("automationEnabled"),
                    requireAndroidAuto = root.getBoolean("requireAndroidAuto"),
                    slots = slots.sortedBy { it.id },
                    radarConfig = RadarConfig(radarEnabled, radarAddress, radarName),
                    // v1/v2 backups had no switch; choose the safe default.
                    waitForGpsFix = if (root.has("waitForGpsFix")) root.getBoolean("waitForGpsFix") else true
                )
            } catch (e: JSONException) {
                throw IllegalArgumentException("Uszkodzony lub nieprawidłowy plik JSON.", e)
            }
        }
    }
}
