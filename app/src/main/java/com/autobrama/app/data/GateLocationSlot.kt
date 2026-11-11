package com.autobrama.app.data

import org.json.JSONArray
import org.json.JSONObject

data class GateLocationSlot(
    val id: Int,
    val name: String,
    val enabled: Boolean,
    val phoneNumber: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
    val hysteresisMeters: Float = 150.0f
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("enabled", enabled)
            put("phoneNumber", phoneNumber)
            put("latitude", latitude)
            put("longitude", longitude)
            put("radiusMeters", radiusMeters.toDouble())
            put("hysteresisMeters", hysteresisMeters.toDouble())
        }
    }

    companion object {
        fun fromJsonObject(json: JSONObject): GateLocationSlot {
            return GateLocationSlot(
                id = json.optInt("id", 1),
                name = json.optString("name", "Brama"),
                enabled = json.optBoolean("enabled", false),
                phoneNumber = json.optString("phoneNumber", ""),
                latitude = json.optDouble("latitude", 52.2297),
                longitude = json.optDouble("longitude", 21.0122),
                radiusMeters = json.optDouble("radiusMeters", 50.0).toFloat(),
                hysteresisMeters = json.optDouble("hysteresisMeters", 150.0).toFloat()
            )
        }

        fun defaultSlots(): List<GateLocationSlot> = listOf(
            GateLocationSlot(
                id = 1,
                name = "Brama 1 (Dom)",
                enabled = false,
                phoneNumber = "",
                latitude = 52.2297,
                longitude = 21.0122,
                radiusMeters = 50.0f
            ),
            GateLocationSlot(
                id = 2,
                name = "Brama 2 (Firma)",
                enabled = false,
                phoneNumber = "",
                latitude = 52.2350,
                longitude = 21.0200,
                radiusMeters = 50.0f
            ),
            GateLocationSlot(
                id = 3,
                name = "Brama 3 (Działka)",
                enabled = false,
                phoneNumber = "",
                latitude = 52.2400,
                longitude = 21.0300,
                radiusMeters = 50.0f
            )
        )

        fun listToJson(slots: List<GateLocationSlot>): String {
            val array = JSONArray()
            slots.forEach { array.put(it.toJsonObject()) }
            return array.toString()
        }

        fun listFromJson(jsonStr: String?): List<GateLocationSlot> {
            if (jsonStr.isNullOrBlank()) return defaultSlots()
            return try {
                val array = JSONArray(jsonStr)
                val result = mutableListOf<GateLocationSlot>()
                for (i in 0 until array.length()) {
                    result.add(fromJsonObject(array.getJSONObject(i)))
                }
                if (result.size < 3) {
                    val defaults = defaultSlots()
                    for (i in result.size until 3) {
                        result.add(defaults[i])
                    }
                }
                result
            } catch (e: Exception) {
                defaultSlots()
            }
        }
    }
}
