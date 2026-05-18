package com.example.hearth.state

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Persists [ChatMessage] history to SharedPreferences so the conversation
 * survives app restart. Called from the ViewModel — load() once on init,
 * save() whenever the history changes.
 *
 * For this to work, [ChatMessage] must be @Serializable (kotlinx.serialization).
 */
object ChatHistoryStore {
    private const val PREFS = "hearth_chat_history"
    private const val KEY_HISTORY = "history_json_v1"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSerializer = ListSerializer(ChatMessage.serializer())

    fun load(context: Context): List<ChatMessage> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            json.decodeFromString(listSerializer, raw)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun save(context: Context, history: List<ChatMessage>) {
        try {
            val str = json.encodeToString(listSerializer, history)
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_HISTORY, str).apply()
        } catch (_: Throwable) {
            // SharedPreferences write failed — non-fatal; in-memory history still works.
        }
    }

    fun clear(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_HISTORY).apply()
    }
}

@Serializable
data class ChatMessageSurrogate(
    val role: String,
    val text: String,
    val tsMs: Long,
)
