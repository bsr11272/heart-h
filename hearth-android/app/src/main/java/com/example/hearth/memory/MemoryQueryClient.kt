package com.example.hearth.memory

import com.example.hearth.mqtt.HearthMqttClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Request/response client over MQTT for the KuzuDB memory graph on Coral.
 * Publishes to `memory/query`, awaits matching `memory/response` keyed by id.
 *
 * Pending requests live in a ConcurrentHashMap; timeouts auto-clean up.
 */
class MemoryQueryClient(
    private val mqtt: HearthMqttClient,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val pending = ConcurrentHashMap<String, CompletableDeferred<MemoryQueryResponse>>()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    init {
        scope.launch {
            mqtt.messages
                .filter { it.topic == "memory/response" }
                .collect { msg ->
                    runCatching { parseResponse(msg.payload) }.getOrNull()?.let { resp ->
                        pending.remove(resp.id)?.complete(resp)
                    }
                }
        }
    }

    suspend fun query(
        req: MemoryQueryRequest,
        timeoutMs: Long = 4000,
    ): MemoryQueryResponse? {
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<MemoryQueryResponse>()
        pending[id] = deferred
        return try {
            val payload = serializeRequest(id, req)
            runCatching { mqtt.publish("memory/query", payload) }
                .onFailure { return null }
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    private fun serializeRequest(id: String, req: MemoryQueryRequest): String {
        val obj = buildJsonObject {
            put("id", id)
            when (req) {
                is MemoryQueryRequest.RecentDeltas -> {
                    put("type", "recent_deltas")
                    req.src?.let { put("src", it) }
                    put("since_min", req.sinceMin)
                    put("limit", req.limit)
                }
                is MemoryQueryRequest.RecentSummaries -> {
                    put("type", "recent_summaries")
                    put("level", req.level)
                    put("since_min", req.sinceMin)
                }
                is MemoryQueryRequest.EntityObservations -> {
                    put("type", "entity_observations")
                    put("entity", req.entityId)
                    put("since_min", req.sinceMin)
                }
            }
        }
        return obj.toString()
    }

    private fun parseResponse(payload: String): MemoryQueryResponse? {
        val o = json.parseToJsonElement(payload).jsonObject
        val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val ok = o["ok"]?.jsonPrimitive?.booleanOrNull ?: false
        val rows = o["rows"]?.jsonArray?.mapNotNull { (it as? JsonObject) }
        val error = o["error"]?.jsonPrimitive?.contentOrNull
        return MemoryQueryResponse(id = id, ok = ok, rows = rows, error = error)
    }
}

sealed class MemoryQueryRequest {
    data class RecentDeltas(
        val src: String?,
        val sinceMin: Int,
        val limit: Int,
    ) : MemoryQueryRequest()

    data class RecentSummaries(
        val level: Int,
        val sinceMin: Int,
    ) : MemoryQueryRequest()

    data class EntityObservations(
        val entityId: String,
        val sinceMin: Int,
    ) : MemoryQueryRequest()
}

data class MemoryQueryResponse(
    val id: String,
    val ok: Boolean,
    val rows: List<JsonObject>?,
    val error: String?,
)
