package com.example.hearth.state

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateMapOf

/**
 * App-wide live map of MQTT device IDs → human-friendly room labels.
 *
 * Backed by a Compose [mutableStateMapOf] so any composable reading via
 * [roomLabel] auto-recomposes when a label is edited. Edits are persisted
 * to SharedPreferences (key prefix "room.label.<id>") so they survive
 * app restarts. Defaults seed from [DEFAULT_ROOM_LABELS].
 */
object RoomLabels {
    private const val PREFS_NAME = "hearth_room_labels"
    private const val PREF_PREFIX = "room.label."

    val labels = mutableStateMapOf<String, String>().apply {
        putAll(DEFAULT_ROOM_LABELS)
    }

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        // Overlay persisted user edits on top of the defaults.
        p.all.forEach { (key, value) ->
            if (key.startsWith(PREF_PREFIX) && value is String && value.isNotBlank()) {
                labels[key.removePrefix(PREF_PREFIX)] = value
            }
        }
    }

    fun set(deviceId: String, label: String) {
        val trimmed = label.trim()
        if (trimmed.isBlank()) {
            // Empty input → revert to default seed (or device id if no default).
            val default = DEFAULT_ROOM_LABELS[deviceId]
            if (default != null) labels[deviceId] = default
            else labels.remove(deviceId)
            prefs?.edit()?.remove(PREF_PREFIX + deviceId)?.apply()
        } else {
            labels[deviceId] = trimmed
            prefs?.edit()?.putString(PREF_PREFIX + deviceId, trimmed)?.apply()
        }
    }

    fun get(deviceId: String): String = labels[deviceId] ?: deviceId
}
