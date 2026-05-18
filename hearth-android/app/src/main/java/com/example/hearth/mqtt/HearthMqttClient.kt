package com.example.hearth.mqtt

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

/**
 * Thin wrapper around HiveMQ MQTT Client (MQTT v5) for Hearth.
 *
 * Topology (per project-plan-v7.3.md): the Coral runs Mosquitto on 0.0.0.0:1883
 * (anonymous during bring-up). This client subscribes to the unified delta
 * topics: pendant/+, radar/+, camera/+, plus availability LWT topics.
 *
 * Threading: the HiveMQ async client is non-blocking. We expose received
 * messages via [messages] (a SharedFlow) so multiple Compose collectors
 * can observe without stealing from each other.
 */
class HearthMqttClient {

    private var client: Mqtt3AsyncClient? = null
    private val _messages = MutableSharedFlow<MqttMessage>(
        replay = 0,
        extraBufferCapacity = 256,
    )
    val messages: SharedFlow<MqttMessage> = _messages.asSharedFlow()

    private val _state = MutableSharedFlow<ConnectionState>(
        replay = 1,
        extraBufferCapacity = 4,
    )
    val state: SharedFlow<ConnectionState> = _state.asSharedFlow()

    /** Connect to [host]:[port]. Suspends until the connect either completes or fails. */
    suspend fun connect(host: String, port: Int = 1883) {
        disconnect()
        _state.emit(ConnectionState.Connecting(host, port))

        val c = MqttClient.builder()
            .useMqttVersion3()
            .identifier("hearth-android-${UUID.randomUUID()}")
            .serverHost(host)
            .serverPort(port)
            .automaticReconnectWithDefaultConfig()
            .addConnectedListener {
                _state.tryEmit(ConnectionState.Connected(host, port))
            }
            .addDisconnectedListener { ctx ->
                _state.tryEmit(ConnectionState.Disconnected(ctx.cause?.message))
            }
            .buildAsync()

        client = c

        c.connect().await()

        // Subscribe to all Hearth sensor families with multi-level wildcards
        // so we catch ESPHome's deeper topic trees (ld2450/<node>/sensor/<id>/state).
        listOf("pendant/#", "radar/#", "ld2450/#", "camera/#", "audio/#", "memory/#", "test/#").forEach { topic ->
            c.subscribeWith()
                .topicFilter(topic)
                .qos(MqttQos.AT_MOST_ONCE)
                .callback { pub: Mqtt3Publish ->
                    val msg = MqttMessage(
                        topic = pub.topic.toString(),
                        payload = pub.payloadAsBytes.decodeToString(),
                    )
                    _messages.tryEmit(msg)
                }
                .send()
                .await()
        }
    }

    fun disconnect() {
        client?.disconnect()
        client = null
    }

    /** Publish a one-off message (handy for testing from the app itself). */
    suspend fun publish(topic: String, payload: String) {
        val c = client ?: return
        c.publishWith()
            .topic(topic)
            .payload(payload.toByteArray())
            .qos(MqttQos.AT_MOST_ONCE)
            .send()
            .await()
    }
}

data class MqttMessage(
    val topic: String,
    val payload: String,
    val tsMs: Long = System.currentTimeMillis(),
)

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data class Connecting(val host: String, val port: Int) : ConnectionState
    data class Connected(val host: String, val port: Int) : ConnectionState
    data class Disconnected(val reason: String?) : ConnectionState
}

/** Bridge HiveMQ CompletableFuture -> coroutine suspend. */
private suspend fun <T> java.util.concurrent.CompletableFuture<T>.await(): T =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        whenComplete { value, error ->
            if (error != null) cont.resumeWith(Result.failure(error))
            else cont.resumeWith(Result.success(value))
        }
        cont.invokeOnCancellation { cancel(true) }
    }

/** Helper to bridge HiveMQ's callback API to a Flow if a caller prefers that. */
@Suppress("unused")
fun mqttCallbackFlow(): Flow<MqttMessage> = callbackFlow {
    awaitClose { /* nothing — HearthMqttClient owns the lifecycle */ }
}
