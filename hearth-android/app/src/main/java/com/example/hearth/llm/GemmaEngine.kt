package com.example.hearth.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Thin coroutine wrapper around LiteRT-LM's [Engine] for Gemma 4 on-device inference.
 *
 * Model file is expected at the app's external-files dir so we can `adb push`
 * it onto the device without runtime permissions:
 *   adb push gemma-4-E2B-it.litertlm \
 *     /sdcard/Android/data/com.example.hearth/files/
 *
 * [Engine.initialize] takes up to ~10s on first load; always called on Dispatchers.IO.
 */
class GemmaEngine(private val context: Context) {

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private var engine: Engine? = null

    val modelFile: File
        get() = File(context.getExternalFilesDir(null), DEFAULT_MODEL_FILE_NAME)

    /** Load the model file from external private storage. Idempotent. */
    suspend fun loadDefault() = loadFrom(modelFile)

    suspend fun loadFrom(file: File) = withContext(Dispatchers.IO) {
        if (engine != null) {
            _state.value = EngineState.Ready(modelFile = file)
            return@withContext
        }
        if (!file.exists()) {
            _state.value = EngineState.Error(
                "Model not found at ${file.absolutePath}. " +
                "adb push the .litertlm file there first."
            )
            return@withContext
        }
        _state.value = EngineState.Loading(file.absolutePath)
        try {
            val e = Engine(EngineConfig(modelPath = file.absolutePath))
            e.initialize()
            engine = e
            _state.value = EngineState.Ready(file)
            Log.i(TAG, "engine ready: ${file.absolutePath} (${file.length() / 1_000_000} MB)")
        } catch (t: Throwable) {
            Log.e(TAG, "engine init failed", t)
            _state.value = EngineState.Error("init failed: ${t.message ?: t::class.simpleName}")
        }
    }

    /** Synchronous prompt → response. Blocks the calling coroutine on IO. */
    suspend fun ask(prompt: String): String = withContext(Dispatchers.IO) {
        val e = engine ?: throw IllegalStateException("engine not loaded")
        e.createConversation().use { conv ->
            val message = conv.sendMessage(prompt)
            messageToString(message)
        }
    }

    /**
     * LiteRT-LM 0.10 returns a Message object from sendMessage. Different versions
     * use different field names (`text`, `content`, `body`), so reflect to be safe.
     */
    private fun messageToString(message: Any?): String {
        if (message == null) return ""
        if (message is String) return message
        // Try common property names via reflection
        listOf("text", "content", "body", "value", "response").forEach { name ->
            try {
                val field = message::class.java.getDeclaredField(name)
                field.isAccessible = true
                val v = field.get(message)
                if (v is String) return v
                if (v != null) return v.toString()
            } catch (_: NoSuchFieldException) { /* try next */ }
            try {
                val getter = message::class.java.getMethod(
                    "get" + name.replaceFirstChar { it.uppercase() }
                )
                val v = getter.invoke(message)
                if (v is String) return v
                if (v != null) return v.toString()
            } catch (_: NoSuchMethodException) { /* try next */ }
        }
        return message.toString()
    }

    /** Free GPU/NPU resources. Call from ViewModel.onCleared. */
    fun close() {
        engine?.close()
        engine = null
        _state.value = EngineState.Idle
    }

    companion object {
        private const val TAG = "GemmaEngine"
        // Matches the file we adb-push from the dev box for iteration 2
        const val DEFAULT_MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
    }
}

sealed interface EngineState {
    data object Idle : EngineState
    data class Loading(val path: String) : EngineState
    data class Ready(val modelFile: File) : EngineState
    data class Error(val message: String) : EngineState
}
