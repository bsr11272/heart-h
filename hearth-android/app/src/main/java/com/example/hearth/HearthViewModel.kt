package com.example.hearth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hearth.improv.ImprovBleClient
import com.example.hearth.improv.ImprovDevice
import com.example.hearth.improv.ProvisionProgress
import com.example.hearth.llm.EngineState
import com.example.hearth.llm.GemmaEngine
import com.example.hearth.mqtt.ConnectionState
import com.example.hearth.mqtt.HearthMqttClient
import com.example.hearth.mqtt.MqttMessage
import com.example.hearth.memory.MemoryQueryClient
import com.example.hearth.memory.MemoryQueryRequest
import com.example.hearth.memory.MemoryQueryResponse
import com.example.hearth.memory.classifyMemoryIntent
import com.example.hearth.notifications.FallNotifier
import com.example.hearth.state.ChatHistoryStore
import com.example.hearth.state.ChatMessage
import com.example.hearth.state.GaitHistoryStore
import com.example.hearth.state.SensorState
import com.example.hearth.state.alertFusion
import com.example.hearth.state.apply
import com.example.hearth.state.toGemmaContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HearthViewModel(app: Application) : AndroidViewModel(app) {

    private val client = HearthMqttClient()
    private val gemma = GemmaEngine(app)
    private val improv = ImprovBleClient(app)
    val memoryClient = MemoryQueryClient(client, viewModelScope)
    private var scanJob: Job? = null

    private val _ui = MutableStateFlow(
        UiState(
            host = DEFAULT_BROKER_HOST,
            port = DEFAULT_BROKER_PORT.toString(),
            connection = ConnectionState.Idle,
            messages = emptyList(),
            sensorState = SensorState(),
            engine = EngineState.Idle,
            chatHistory = emptyList(),
            chatInput = "",
            inferenceInFlight = false,
            setupOpen = false,
            scanning = false,
            discovered = emptyList(),
            wifiSsid = "",
            wifiPassword = "",
            provisioningDevice = null,
            provisionStatus = "",
            fallDismissed = false,
        )
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        FallNotifier.ensureChannel(app)
        // Restore persisted chat history (survives app kill/restart)
        val persisted = ChatHistoryStore.load(app)
        if (persisted.isNotEmpty()) {
            _ui.update { it.copy(chatHistory = persisted) }
        }
        // Restore persisted gait history so the Health tab sparkline is
        // "warm" the moment the app opens, instead of empty until the
        // next gait event arrives.
        val persistedGait = GaitHistoryStore.load(app)
        if (persistedGait.isNotEmpty()) {
            _ui.update { it.copy(
                sensorState = it.sensorState.copy(gaitHistory = persistedGait),
            ) }
        }
        // Auto-save on every change
        viewModelScope.launch {
            ui.map { it.chatHistory }.distinctUntilChanged().collect { history ->
                ChatHistoryStore.save(getApplication(), history)
            }
        }
        viewModelScope.launch {
            ui.map { it.sensorState.gaitHistory }.distinctUntilChanged().collect { hist ->
                GaitHistoryStore.save(getApplication(), hist)
            }
        }
        viewModelScope.launch {
            client.messages.collect { msg ->
                _ui.update { state ->
                    val nextLog = (state.messages + msg).takeLast(MAX_MESSAGES)
                    val nextState = state.sensorState.apply(msg)
                    state.copy(messages = nextLog, sensorState = nextState)
                }
            }
        }
        viewModelScope.launch {
            client.state.collect { st -> _ui.update { it.copy(connection = st) } }
        }
        viewModelScope.launch {
            gemma.state.collect { st -> _ui.update { it.copy(engine = st) } }
        }
        // Auto-connect to the default broker on cold launch so the demo
        // works without anyone tapping into the Setup tab. If it fails
        // the Connect button on Setup still re-tries manually.
        viewModelScope.launch {
            val st = _ui.value
            runCatching { client.connect(st.host, st.port.toIntOrNull() ?: DEFAULT_BROKER_PORT) }
                .onFailure { err ->
                    _ui.update {
                        it.copy(connection = ConnectionState.Disconnected(err.message))
                    }
                }
        }
        // Edge-triggered fall notifier — fire only on false→true transitions
        viewModelScope.launch {
            var prevPendant = false
            var prevVision  = false
            ui.collect { s ->
                val p = s.sensorState.pendantFallActive
                val v = s.sensorState.visionFallActive
                if ((p && !prevPendant) || (v && !prevVision)) {
                    val src = listOfNotNull(
                        if (p) "Pozyx pendant" else null,
                        if (v) "Edge TPU vision" else null,
                    ).joinToString(" + ")
                    val notes = listOfNotNull(
                        s.sensorState.pendantFallNotes?.takeIf { p },
                        s.sensorState.visionFallNotes?.takeIf { v },
                    ).joinToString(" · ").ifBlank { null }
                    FallNotifier.postFallAlert(getApplication(), src, notes)
                    _ui.update { it.copy(fallDismissed = false) }
                }
                prevPendant = p; prevVision = v
            }
        }
    }

    fun dismissFall() = _ui.update { it.copy(fallDismissed = true) }

    // ----- MQTT -----
    fun setHost(h: String) = _ui.update { it.copy(host = h.trim()) }
    fun setPort(p: String) = _ui.update { it.copy(port = p.filter { c -> c.isDigit() }) }

    fun onConnectClicked() {
        val state = _ui.value
        when (state.connection) {
            is ConnectionState.Connected -> client.disconnect()
            else -> {
                val port = state.port.toIntOrNull() ?: DEFAULT_BROKER_PORT
                viewModelScope.launch {
                    runCatching { client.connect(state.host, port) }
                        .onFailure { err ->
                            _ui.update {
                                it.copy(connection = ConnectionState.Disconnected(err.message))
                            }
                        }
                }
            }
        }
    }

    fun publishTest() {
        viewModelScope.launch {
            runCatching { client.publish("test/hearth-app", "hello from android v0.4") }
        }
    }

    // ----- Gemma chat -----
    fun onLoadModelClicked() {
        viewModelScope.launch { gemma.loadDefault() }
    }

    fun setChatInput(s: String) = _ui.update { it.copy(chatInput = s) }

    fun sendChat() {
        val st = _ui.value
        val input = st.chatInput.trim()
        if (input.isBlank() || st.inferenceInFlight) return
        if (st.engine !is EngineState.Ready) return
        val userMsg = ChatMessage.now(ChatMessage.Role.USER, input)
        _ui.update { it.copy(
            chatHistory = it.chatHistory + userMsg,
            chatInput = "",
            inferenceInFlight = true,
        ) }
        viewModelScope.launch {
            // Decide if the prompt needs the memory graph; fetch deltas OR
            // summaries depending on the time window. Short windows (≤15min)
            // hit raw deltas because 5-min summaries don't exist until a
            // window CLOSES; longer windows use hierarchical summaries.
            val intent = classifyMemoryIntent(input)
            val memoryContext: String? = if (intent.needsMemory) {
                val resp = if (intent.preferredLevel <= 1) {
                    val req = MemoryQueryRequest.RecentDeltas(
                        src = intent.srcHint,
                        sinceMin = intent.timeWindowMin,
                        limit = 200,
                    )
                    runCatching { memoryClient.query(req) }.getOrNull()
                } else {
                    val req = MemoryQueryRequest.RecentSummaries(
                        level = intent.preferredLevel,
                        sinceMin = intent.timeWindowMin,
                    )
                    runCatching { memoryClient.query(req) }.getOrNull()
                }
                resp?.let { formatMemoryContext(it, intent) }
            } else null

            val prompt = buildChatPrompt(_ui.value, memoryContext)
            val resp = runCatching { gemma.ask(prompt) }
                .getOrElse { "ERROR: ${it.message}" }
            val asstMsg = ChatMessage.now(ChatMessage.Role.ASSISTANT, resp)
            _ui.update { it.copy(
                chatHistory = it.chatHistory + asstMsg,
                inferenceInFlight = false,
            ) }
        }
    }

    private fun formatMemoryContext(
        resp: MemoryQueryResponse,
        intent: com.example.hearth.memory.MemoryIntent,
    ): String? {
        if (!resp.ok) return null
        val rows = resp.rows.orEmpty()
        if (rows.isEmpty()) return null
        return if (intent.preferredLevel <= 1) {
            formatDeltaResponse(rows, intent.timeWindowMin)
        } else {
            formatSummaryResponse(rows)
        }
    }

    /** Condense potentially-hundreds of raw deltas into a meaningful event timeline.
     *  Detects presence transitions (OFF↔ON per radar), person detections (camera
     *  add/remove with cls=person), and fall events. Drops the noisy stuff (radar
     *  XY state spam, ESPHome discovery telemetry). */
    private fun formatDeltaResponse(rows: List<JsonObject>, windowMin: Int): String? {
        if (rows.isEmpty()) return null
        val now = System.currentTimeMillis()

        data class TLEvent(val ts: Long, val text: String)
        val events = mutableListOf<TLEvent>()
        val presenceState = mutableMapOf<String, String>() // device -> last seen ON/OFF

        // Chronological order so we can detect ON→OFF transitions correctly.
        val chrono = rows.sortedBy { it["d.ts"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L }

        chrono.forEach { row ->
            val topic = row.string("d.topic") ?: return@forEach
            val op = row.string("d.op") ?: ""
            val ts = row["d.ts"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return@forEach
            val payload = row.string("d.payload") ?: ""

            when {
                topic.endsWith("/binary_sensor/presence/state") -> {
                    val device = topic.split('/').getOrNull(1) ?: return@forEach
                    val prev = presenceState[device]
                    if (prev != payload) {
                        val label = com.example.hearth.state.roomLabel(device)
                        when (payload) {
                            "ON"  -> events.add(TLEvent(ts, "$label now has presence"))
                            "OFF" -> if (prev != null) events.add(TLEvent(ts, "$label became empty"))
                        }
                        presenceState[device] = payload
                    }
                }
                op == "add" || op == "remove" -> {
                    val cam = topic.split('/').getOrNull(1) ?: return@forEach
                    val cls = extractJsonField(payload, "cls")
                    if (cls == "person") {
                        val label = com.example.hearth.state.roomLabel(cam)
                        val verb = if (op == "add") "saw a person enter" else "person left view of"
                        events.add(TLEvent(ts, "$label camera $verb"))
                    }
                }
                op == "fall_event" -> {
                    val src = topic.split('/').getOrNull(0) ?: ""
                    val device = topic.split('/').getOrNull(1) ?: ""
                    val label = if (device.isNotEmpty()) com.example.hearth.state.roomLabel(device) else src
                    events.add(TLEvent(ts, "🚨 FALL detected in $label"))
                }
            }
        }

        // Newest first, cap at 12 lines.
        val timeline = events.sortedByDescending { it.ts }.take(12)
        if (timeline.isEmpty()) {
            // No transitions in the window — derive a "latest known state"
            // from the most recent radar presence + the current snapshot so
            // Gemma has SOMETHING factual to narrate instead of giving up.
            val bySrc = rows.groupingBy { it.string("d.src") ?: "?" }.eachCount()
            val lastPresence = mutableMapOf<String, Pair<String, Long>>() // device -> (state, ts)
            chrono.forEach { row ->
                val topic = row.string("d.topic") ?: return@forEach
                if (topic.endsWith("/binary_sensor/presence/state")) {
                    val device = topic.split('/').getOrNull(1) ?: return@forEach
                    val ts = row["d.ts"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return@forEach
                    lastPresence[device] = (row.string("d.payload") ?: "") to ts
                }
            }
            return buildString {
                appendLine("In the last $windowMin minutes: $${rows.size} sensor updates")
                appendLine("(${bySrc.entries.joinToString(", ") { "${it.value} from ${it.key}" }}),")
                appendLine("no presence transitions detected. Latest known state per sensor:")
                lastPresence.forEach { (device, p) ->
                    val ageSec = ((now - p.second) / 1000).toInt()
                    val ago = if (ageSec < 60) "${ageSec}s ago" else "${ageSec / 60}m ago"
                    appendLine("- ${com.example.hearth.state.roomLabel(device)} ($device): ${p.first} as of $ago")
                }
            }.trimEnd()
        }
        return buildString {
            appendLine("Activity in the last $windowMin minutes (newest first):")
            timeline.forEach { e ->
                val ageMin = ((now - e.ts) / 60_000).toInt()
                val ageSec = ((now - e.ts) / 1000).toInt()
                val ago = when {
                    ageSec < 10  -> "just now"
                    ageMin == 0  -> "${ageSec}s ago"
                    ageMin == 1  -> "1 min ago"
                    else         -> "${ageMin} min ago"
                }
                appendLine("- $ago: ${e.text}")
            }
        }.trimEnd()
    }

    /** Extract one top-level string field from a JSON payload, or null. */
    private fun extractJsonField(payload: String, key: String): String? = try {
        kotlinx.serialization.json.Json.parseToJsonElement(payload)
            .jsonObject[key]?.jsonPrimitive?.contentOrNull
    } catch (_: Throwable) { null }

    private fun formatSummaryResponse(rows: List<JsonObject>): String? {
        val lines = rows.take(20).mapNotNull { row ->
            val ts = row.string("s.start_ts")
            val src = row.string("s.src")
            val text = row.string("s.text") ?: return@mapNotNull null
            val tag = src?.let { "[$it]" } ?: ""
            listOfNotNull(ts, tag).joinToString(" ").let { prefix -> "$prefix $text".trim() }
        }
        if (lines.isEmpty()) return null
        return buildString {
            appendLine("Recent memory:")
            lines.forEach { appendLine("- $it") }
        }.trimEnd()
    }

    /** Try to extract a room label from a topic like 'camera/kitchen/...' or 'ld2450/ld2450-b/...'. */
    private fun roomLabelForTopic(topic: String): String {
        val parts = topic.split('/')
        return when {
            parts.size >= 2 && parts[0] == "camera"  -> com.example.hearth.state.roomLabel(parts[1])
            parts.size >= 2 && parts[0] == "ld2450"  -> com.example.hearth.state.roomLabel(parts[1])
            parts.isNotEmpty() && parts[0] == "pendant" -> "pendant"
            else -> topic
        }
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    /**
     * Structured Gemma 3/4 system prompt + turns.
     *
     * Layout follows the Gemma 4 caregiving-agent pattern (gemma4.wiki +
     * Gemma cookbook prompting-techniques chapter):
     *   1. Explicit role / audience / refusal policy block.
     *   2. Compact ambient snapshot (small Gemmas degrade past ~1 KB of
     *      context, so we condense rather than dump).
     *   3. Optional pre-filtered recent-memory block (only when the user
     *      asked a past-tense question -- the graph query upstream).
     *   4. Fusion-aware output shape: if the alert state is CONFIRMED
     *      the model escalates in one sentence; if UNCONFIRMED_ANOMALOUS
     *      it issues a soft check-in; if NONE it answers normally.
     *   5. Last-4 turns of chat history, then the "Hearth:" cue.
     */
    private fun buildChatPrompt(st: UiState, memoryContext: String? = null): String = buildString {
        val s = st.sensorState
        val fusion = s.alertFusion()

        // ----- system block -------------------------------------------------
        appendLine("# Role")
        appendLine("You are Hearth, the in-home AI companion for Margaret (79, lives alone, mild")
        appendLine("glaucoma, sometimes forgets to take her evening pills). Two audiences read")
        appendLine("your replies: Margaret herself (read aloud by the smart speaker) and her")
        appendLine("daughter Anna (in-app caregiver view).")
        appendLine()
        appendLine("# Voice")
        appendLine("Warm, grounded, brief. Concrete. Never alarming when not needed. Sound like")
        appendLine("a thoughtful daughter or trusted nurse who has been watching the house all")
        appendLine("day. Default reply length: <= 60 words for greetings or check-ins, <= 120")
        appendLine("words for explanations. No bullet lists unless explicitly asked.")
        appendLine()
        appendLine("# Scope and refusal")
        appendLine("You can only see what the SNAPSHOT below says is happening right now, plus")
        appendLine("the optional MEMORY block. You cannot control devices, place phone calls,")
        appendLine("or take real-world actions. If the user asks for medical diagnosis, defer:")
        appendLine("\"I can't diagnose -- if something feels wrong, please tell Anna or dial 911.\"")
        appendLine()
        appendLine("# Snapshot (right now, authoritative)")
        appendLine(s.toGemmaContext())
        appendLine()
        appendLine("# Alert fusion state: ${fusion.name}")
        when (fusion) {
            com.example.hearth.state.AlertFusion.CONFIRMED -> {
                appendLine("A fall or matched audio+vision event was just confirmed. If the")
                appendLine("incoming user message is anything other than \"yes, she's ok\" you")
                appendLine("MUST respond in one short sentence escalating to Anna -- e.g.")
                appendLine("\"Calling Anna now -- Margaret has fallen in the ${s.pendantRoom ?: "house"}.\"")
            }
            com.example.hearth.state.AlertFusion.UNCONFIRMED_ANOMALOUS -> {
                appendLine("An audio alert just fired but no fall was confirmed. Respond with")
                appendLine("a single warm check-in line addressed to MARGARET (not Anna) --")
                appendLine("she will hear it spoken on the speaker. Example: \"Hi Margaret, I")
                appendLine("heard a noise -- everything okay?\" Keep it under 20 words.")
            }
            com.example.hearth.state.AlertFusion.NONE -> {
                // normal path
            }
        }

        // ----- optional memory excerpt (tool-return-style) ------------------
        if (!memoryContext.isNullOrBlank()) {
            appendLine()
            appendLine("# Memory (graph excerpt, treat as a tool return)")
            appendLine(memoryContext)
        }

        // ----- turns --------------------------------------------------------
        appendLine()
        appendLine("# Conversation (last few turns)")
        st.chatHistory.takeLast(4).forEach { m ->
            val tag = when (m.role) {
                ChatMessage.Role.USER -> "User"
                ChatMessage.Role.ASSISTANT -> "Hearth"
                ChatMessage.Role.SYSTEM -> "System"
            }
            appendLine("$tag: ${m.text}")
        }
        appendLine("Hearth:")
    }

    fun clearChat() = _ui.update { it.copy(chatHistory = emptyList(), chatInput = "") }

    // ----- Improv-WiFi BLE provisioning -----
    fun openSetup() = _ui.update {
        it.copy(setupOpen = true, discovered = emptyList(), provisionStatus = "")
    }
    fun closeSetup() {
        stopScan()
        _ui.update { it.copy(setupOpen = false, provisioningDevice = null) }
    }

    fun startScan() {
        scanJob?.cancel()
        _ui.update { it.copy(scanning = true, discovered = emptyList()) }
        scanJob = viewModelScope.launch {
            runCatching {
                improv.scan().collect { dev ->
                    _ui.update { state ->
                        val without = state.discovered.filter { it.address != dev.address }
                        state.copy(discovered = (without + dev).sortedByDescending { it.rssi })
                    }
                }
            }.onFailure { err ->
                _ui.update { it.copy(scanning = false, provisionStatus = "scan failed: ${err.message}") }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel(); scanJob = null
        _ui.update { it.copy(scanning = false) }
    }

    fun setWifiSsid(v: String) = _ui.update { it.copy(wifiSsid = v) }
    fun setWifiPassword(v: String) = _ui.update { it.copy(wifiPassword = v) }

    fun provision(device: ImprovDevice) {
        val st = _ui.value
        if (st.wifiSsid.isBlank()) {
            _ui.update { it.copy(provisionStatus = "enter SSID first") }
            return
        }
        stopScan()
        _ui.update { it.copy(provisioningDevice = device, provisionStatus = "connecting…") }
        viewModelScope.launch {
            runCatching {
                improv.provision(device, st.wifiSsid, st.wifiPassword) { progress ->
                    val msg = when (progress) {
                        is ProvisionProgress.Connecting     -> "connecting…"
                        is ProvisionProgress.Connected      -> "connected, discovering services…"
                        is ProvisionProgress.Discovered     -> "Improv service found, subscribing…"
                        is ProvisionProgress.State          -> "state byte 0x%02X".format(progress.raw)
                        is ProvisionProgress.SentCredentials-> "credentials sent, waiting for IP…"
                        is ProvisionProgress.Provisioned    -> "device URLs: ${progress.urls.joinToString()}"
                    }
                    _ui.update { it.copy(provisionStatus = msg) }
                }
            }.onSuccess { urls ->
                _ui.update {
                    it.copy(
                        provisionStatus = "PROVISIONED — ${urls.joinToString(", ")}",
                        provisioningDevice = null,
                    )
                }
            }.onFailure { err ->
                _ui.update {
                    it.copy(
                        provisionStatus = "FAILED: ${err.message}",
                        provisioningDevice = null,
                    )
                }
            }
        }
    }

    override fun onCleared() {
        client.disconnect()
        gemma.close()
        stopScan()
        super.onCleared()
    }

    companion object {
        const val MAX_MESSAGES = 250
        const val DEFAULT_BROKER_HOST = "100.86.78.49"  // Coral via Tailscale
        const val DEFAULT_BROKER_PORT = 1883
    }
}

data class UiState(
    val host: String,
    val port: String,
    val connection: ConnectionState,
    val messages: List<MqttMessage>,
    val sensorState: SensorState,
    val engine: EngineState,
    val chatHistory: List<ChatMessage>,
    val chatInput: String,
    val inferenceInFlight: Boolean,
    // Improv setup mode
    val setupOpen: Boolean,
    val scanning: Boolean,
    val discovered: List<ImprovDevice>,
    val wifiSsid: String,
    val wifiPassword: String,
    val provisioningDevice: ImprovDevice?,
    val provisionStatus: String,
    val fallDismissed: Boolean,
)
