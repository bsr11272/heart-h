package com.example.hearth.state

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Persists [SensorState.gaitHistory] (per-person rolling list of
 * [GaitSnapshot]) to SharedPreferences so the Health-tab sparkline
 * survives app cold-start. Without this the chart is empty until the
 * next gait event arrives -- terrible for a demo where the user has
 * just installed a new APK and force-stopped to pick it up.
 *
 * Discipline mirrors [ChatHistoryStore]: load() on init, save() on
 * every change. Per-person list is capped upstream in
 * [SensorState.applyGait] (MAX_GAIT_HISTORY = 40), so the JSON blob
 * stays small (~40 snapshots * ~100 bytes each * N persons).
 */
object GaitHistoryStore {
    private const val PREFS = "hearth_gait_history"
    private const val KEY_HISTORY = "history_json_v1"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mapSerializer = MapSerializer(
        String.serializer(),
        ListSerializer(GaitSnapshot.serializer()),
    )

    fun load(context: Context): Map<String, List<GaitSnapshot>> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HISTORY, null) ?: return emptyMap()
        return try {
            json.decodeFromString(mapSerializer, raw)
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    fun save(context: Context, history: Map<String, List<GaitSnapshot>>) {
        try {
            val str = json.encodeToString(mapSerializer, history)
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_HISTORY, str).apply()
        } catch (_: Throwable) {
            // Non-fatal — in-memory state still works.
        }
    }

    fun clear(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_HISTORY).apply()
    }
}
