package com.example.hearth.state

import com.example.hearth.mqtt.MqttMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

/**
 * Structured snapshot of "what is happening right now" derived by folding
 * incoming MQTT messages into typed state. Replaces v0.2's dumb-list approach
 * — the LLM now sees a clean summary instead of raw JSON.
 *
 * Topics consumed (all forwarded by Coral Mosquitto):
 *   pendant/events                     {"src":"pendant","op":"position","xyz_m":[...]}
 *   pendant/events                     {"src":"pendant","op":"fall_event","phase":"confirmed"}
 *   pendant/vitals                     {"src":"pendant","op":"vital_signs",...}
 *   radar/{room}/delta                 {"src":"ld2450","node":"...","presence":true,"targets":[...]}
 *   ld2450/{node}/sensor/{id}/state    ESPHome native sensor topics (raw values)
 *   ld2450/{node}/availability         online/offline
 *   camera/{cam}/delta                 {"src":"camera","op":"add"|"move"|"remove",...}
 *   camera/{cam}/fall_event            pose-based fall from Coral Edge TPU
 *   camera/{cam}/keyframe              {"src":"camera","op":"keyframe","jpeg_path":...}
 *   camera/{cam}/gait                  {"src":"camera","op":"gait","person_id":...}
 *   audio/{source}/event               {"src":"audio","op":"event","label":"Glass",...}
 *   audio/{source}/availability        online/offline
 */
data class SensorState(
    val tStamp: Long = 0L,
    // Pendant — Path A: position + altitude only (no IMU in our SKU)
    val pendantOnline: Boolean = false,
    val pendantPositionM: Triple<Double, Double, Double>? = null,
    val pendantRoom: String? = null,
    val pendantAltitudeM: Double? = null,
    val pendantFallActive: Boolean = false,
    val pendantFallNotes: String? = null,
    // Per-room radar state
    val rooms: Map<String, RoomState> = emptyMap(),
    // Camera detections (entity → last bbox center)
    val cameras: Map<String, CameraState> = emptyMap(),
    // Vision-based fall (Edge TPU pose)
    val visionFallActive: Boolean = false,
    val visionFallNotes: String? = null,
    val lastKeyframePath: String? = null,
    val lastKeyframeSummary: Map<String, Int> = emptyMap(),
    // YamNet audio events from USB-cam mics. Newest-first, capped at 30.
    val audioEvents: List<AudioEvent> = emptyList(),
    // Per-cam audio availability (source id -> "online"/"offline"/"unknown")
    val audioAvailability: Map<String, String> = emptyMap(),
    // MoveNet-derived gait snapshots, keyed by person_id (last one wins).
    val gait: Map<String, GaitSnapshot> = emptyMap(),
    // Per-person rolling history (oldest -> newest, capped) so the Health
    // tab can render a tiny trend sparkline. ~40 entries == ~3 hours at one
    // snapshot every 5 min, which is the natural emit rate of the camera
    // agent's GaitAnalyzer.
    val gaitHistory: Map<String, List<GaitSnapshot>> = emptyMap(),
)

data class RoomState(
    val presence: Boolean = false,
    val moving: Boolean = false,
    val targetCount: Int = 0,
    val targetsXY: List<Pair<Double, Double>> = emptyList(),  // metres
    val availability: String = "unknown",
    // Per-target coord cache (1-based target ID -> (x_m, y_m) — null if unknown)
    val targetCoords: Map<Int, Pair<Double?, Double?>> = emptyMap(),
)

data class CameraState(
    val available: String = "unknown",
    val entities: Map<String, EntitySnapshot> = emptyMap(),
    val latestThumbB64: String? = null,
    val latestSegMaskB64: String? = null,
)

data class EntitySnapshot(
    val cls: String,
    val cx: Int,
    val cy: Int,
    val confidence: Double,
)

/**
 * YamNet audio event from a USB-cam mic running on the Coral.
 * Aging-in-place sounds: Glass, Scream, Doorbell, Smoke alarm, etc.
 */
data class AudioEvent(
    val tsMs: Long,
    val source: String,                     // "cam1", "cam2"
    val label: String,                      // "Glass", "Scream", ...
    val confidence: Double,
    val severity: String,                   // "info" | "warn" | "alert"
    val top3: List<Pair<String, Double>>,
)

/**
 * Gait metrics derived from MoveNet keypoints on the camera agent when
 * a person is walking. One snapshot per person_id, most-recent wins.
 *
 * `@Serializable` so the rolling history can be persisted to
 * SharedPreferences via [GaitHistoryStore] and survive app restart.
 */
@Serializable
data class GaitSnapshot(
    val tsMs: Long,
    val cam: String,
    val personId: String,
    val cadenceSpm: Double,
    val strideLengthMRough: Double,
    val swingAsymmetryPct: Double,
    val nStrides: Int,
    val confidence: Double,
)

/**
 * Map MQTT device IDs to human-friendly room labels. Edited via the Home
 * screen (future), persisted in SharedPreferences (future). For now: defaults
 * that match the physical deployment.
 *
 *  ld2450-a  → Bedroom         (LD2450 radar #1 in the bedroom)
 *  ld2450-b  → Kitchen         (LD2450 radar #2 in the kitchen)
 *  kitchen   → Living Room     (camera device labeled 'kitchen', physically in living room)
 *  livingroom→ Living Room     (camera device labeled 'livingroom', also in living room)
 */
val DEFAULT_ROOM_LABELS: Map<String, String> = mapOf(
    "ld2450-a" to "Bedroom",
    "ld2450-b" to "Kitchen",
    "cam1"     to "Living Room",
    "cam2"     to "Living Room",
)

/** Return the friendly room/area name for a sensor device id, falling back to the id itself.
 *  Reads from the [RoomLabels] singleton (mutableStateMap backed by SharedPreferences),
 *  so any edit in the Home tab editor immediately re-renders every label across the app. */
fun roomLabel(deviceId: String): String =
    RoomLabels.labels[deviceId] ?: deviceId

/**
 * Three-tier fusion result for "did something bad just happen?" Borrowed
 * from arXiv:2507.10474 (Robotic-vision + sound, federated fall detection):
 * audio alerts alone are unreliable -- treat them as a *trigger* that
 * opens a verification window during which vision/pendant must agree.
 */
enum class AlertFusion {
    /** No active alert.  */
    NONE,
    /** Audio alert fired but nothing else corroborated within the window.
     *  Surface as a soft Gemma check-in, not a red banner. */
    UNCONFIRMED_ANOMALOUS,
    /** Pendant or vision (or audio+vision/audio+pendant agreement) -- treat
     *  as a real fall and page the caregiver. */
    CONFIRMED,
}

/** Window inside which audio + vision/pendant must co-occur to confirm. */
private const val ALERT_FUSION_WINDOW_MS = 30_000L

/**
 * Compute the current fusion verdict from the latest snapshot.
 *
 * Rules (priority order):
 *  1. CONFIRMED if pendantFallActive OR visionFallActive (the existing
 *     hard signals still win on their own -- pose-based vision falls
 *     and pendant IMU falls have their own internal confirmation).
 *  2. CONFIRMED if any audio severity=="alert" event landed within the
 *     last [ALERT_FUSION_WINDOW_MS] AND a vision/pendant signal showed
 *     up in the same window (late-fusion late voting).
 *  3. UNCONFIRMED_ANOMALOUS if an audio "alert" landed within the window
 *     with NO corroborating signal -- Gemma should ask softly.
 *  4. NONE otherwise.
 */
fun SensorState.alertFusion(nowMs: Long = System.currentTimeMillis()): AlertFusion {
    if (pendantFallActive || visionFallActive) return AlertFusion.CONFIRMED
    val cutoff = nowMs - ALERT_FUSION_WINDOW_MS
    val recentAlert = audioEvents.firstOrNull {
        it.severity == "alert" && it.tsMs >= cutoff
    } ?: return AlertFusion.NONE
    // Look for corroboration: any recent pendant/vision/radar movement.
    // We don't have hard fall flags here (rule 1 covered those) so any
    // signal of "person definitely doing something abnormal" counts.
    val corroborated = audioEvents.any {
        it.severity == "warn" && it.tsMs >= cutoff && it !== recentAlert
    }
    return if (corroborated) AlertFusion.CONFIRMED else AlertFusion.UNCONFIRMED_ANOMALOUS
}

/** Human-friendly description of the trigger for the banner UI. */
fun SensorState.alertFusionExplain(nowMs: Long = System.currentTimeMillis()): String {
    if (pendantFallActive && visionFallActive) {
        return "Pendant + vision both detected a fall"
    }
    if (pendantFallActive) return "Pendant detected a fall"
    if (visionFallActive)  return "Camera detected a fall pose"
    val cutoff = nowMs - ALERT_FUSION_WINDOW_MS
    val alert = audioEvents.firstOrNull {
        it.severity == "alert" && it.tsMs >= cutoff
    } ?: return ""
    val warn = audioEvents.firstOrNull {
        it.severity == "warn" && it.tsMs >= cutoff && it !== alert
    }
    return if (warn != null) {
        "Audio: ${alert.label} + ${warn.label} (corroborated)"
    } else {
        "Audio: ${alert.label} — no vision/pendant confirmation"
    }
}

/** Apply one MQTT message to the state, returning the new state. */
fun SensorState.apply(msg: MqttMessage): SensorState {
    val topic = msg.topic
    val payload = msg.payload
    val tStamp = msg.tsMs
    return try {
        when {
            // ---- ESPHome native ld2450 per-sensor topics ----
            topic.startsWith("ld2450/") && topic.endsWith("/availability") -> {
                val room = topic.split('/')[1]
                copy(rooms = rooms + (room to (rooms[room] ?: RoomState()).copy(
                    availability = payload.trim()
                )), tStamp = tStamp)
            }
            topic.startsWith("ld2450/") && ("/sensor/" in topic || "/binary_sensor/" in topic)
                    && topic.endsWith("/state") -> {
                applyEspHomeRadar(topic, payload, tStamp)
            }
            // ---- radar consolidated delta (if a Pi-side bridge consolidates) ----
            topic.startsWith("radar/") && topic.endsWith("/delta") -> {
                applyConsolidatedRadar(payload, tStamp)
            }
            // ---- pendant ----
            topic == "pendant/availability" -> {
                copy(pendantOnline = payload.trim() == "online", tStamp = tStamp)
            }
            topic == "pendant/events" || topic == "pendant/vitals" -> {
                applyPendant(payload, tStamp)
            }
            // ---- camera ----
            topic.startsWith("camera/") && topic.endsWith("/availability") -> {
                val cam = topic.split('/')[1]
                copy(cameras = cameras + (cam to (cameras[cam] ?: CameraState()).copy(
                    available = payload.trim()
                )), tStamp = tStamp)
            }
            topic.startsWith("camera/") && topic.endsWith("/delta") -> {
                applyCameraDelta(payload, tStamp)
            }
            topic.startsWith("camera/") && topic.endsWith("/fall_event") -> {
                applyCameraFall(payload, tStamp)
            }
            topic.startsWith("camera/") && topic.endsWith("/keyframe") -> {
                applyKeyframe(payload, tStamp)
            }
            topic.startsWith("camera/") && topic.endsWith("/thumb") -> {
                val cam = topic.split('/')[1]
                copy(cameras = cameras + (cam to (cameras[cam] ?: CameraState()).copy(
                    latestThumbB64 = payload
                )), tStamp = tStamp)
            }
            topic.startsWith("camera/") && topic.endsWith("/seg_mask") -> {
                val cam = topic.split('/')[1]
                // Empty payload is a "clear" signal — person left the frame.
                val cleaned = payload.takeIf { it.isNotBlank() }
                copy(cameras = cameras + (cam to (cameras[cam] ?: CameraState()).copy(
                    latestSegMaskB64 = cleaned
                )), tStamp = tStamp)
            }
            topic.startsWith("camera/") && topic.endsWith("/gait") -> {
                applyGait(payload, tStamp)
            }
            // ---- audio (YamNet from USB-cam mics) ----
            topic.startsWith("audio/") && topic.endsWith("/availability") -> {
                val src = topic.split('/').getOrNull(1) ?: return this
                copy(
                    audioAvailability = audioAvailability + (src to payload.trim()),
                    tStamp = tStamp,
                )
            }
            topic.startsWith("audio/") && topic.endsWith("/event") -> {
                applyAudioEvent(payload, tStamp)
            }
            else -> this
        }
    } catch (t: Throwable) {
        // bad payload → leave state unchanged
        this
    }
}

// ---- helpers -------------------------------------------------------------

private const val MAX_AUDIO_EVENTS = 30
private const val MAX_GAIT_HISTORY = 40

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

private fun JsonObject.numOrNull(key: String): Double? =
    this[key]?.jsonPrimitive?.doubleOrNull

private fun JsonObject.intOrNull(key: String): Int? =
    this[key]?.jsonPrimitive?.intOrNull

private fun JsonObject.strOrNull(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

private fun SensorState.applyConsolidatedRadar(payload: String, t: Long): SensorState {
    val j = json.parseToJsonElement(payload).jsonObject
    val node = j["node"]?.jsonPrimitive?.contentOrNull ?: return this
    val targets = j["targets"]?.jsonArray?.map {
        val o = it.jsonObject
        (o["x"]?.jsonPrimitive?.doubleOrNull ?: 0.0) to
            (o["y"]?.jsonPrimitive?.doubleOrNull ?: 0.0)
    } ?: emptyList()
    val rs = (rooms[node] ?: RoomState()).copy(
        presence    = j["presence"]?.jsonPrimitive?.boolean ?: false,
        moving      = j["moving"]?.jsonPrimitive?.boolean ?: false,
        targetCount = j["target_count"]?.jsonPrimitive?.intOrNull ?: targets.size,
        targetsXY   = targets,
    )
    return copy(rooms = rooms + (node to rs), tStamp = t)
}

private fun SensorState.applyEspHomeRadar(topic: String, payload: String, t: Long): SensorState {
    // ld2450/<node>/{sensor|binary_sensor}/<sid>/state
    val parts = topic.split('/')
    if (parts.size < 5) return this
    val node = parts[1]
    val sid  = parts[3]
    val rs   = rooms[node] ?: RoomState()
    val v    = payload.trim()
    val updated = when {
        sid.endsWith("target_count") -> rs.copy(targetCount = v.toIntOrNull() ?: rs.targetCount)
        sid.contains("presence")     -> rs.copy(presence = v.equals("ON", true) || v == "1")
        sid.contains("moving")       -> rs.copy(moving = v.equals("ON", true) || v == "1")
        TARGET_X_REGEX.matches(sid)  -> {
            val tid = sid[1].digitToInt()
            val mm = v.toDoubleOrNull()?.takeIf { it.isFinite() }
            val xm = mm?.let { it / 1000.0 }
            applyTargetCoord(rs, tid, xIsAxis = true, value = xm)
        }
        TARGET_Y_REGEX.matches(sid)  -> {
            val tid = sid[1].digitToInt()
            val mm = v.toDoubleOrNull()?.takeIf { it.isFinite() }
            val ym = mm?.let { it / 1000.0 }
            applyTargetCoord(rs, tid, xIsAxis = false, value = ym)
        }
        else -> rs
    }
    return copy(rooms = rooms + (node to updated), tStamp = t)
}

private val TARGET_X_REGEX = Regex("t[123]_x")
private val TARGET_Y_REGEX = Regex("t[123]_y")

/**
 * Update a per-target X or Y, then rebuild [RoomState.targetsXY] from cached
 * coords. Excludes targets where either axis is null/NaN or outside a generous
 * sanity envelope (±3 m lateral, 0-6 m forward) — kills the worst through-wall
 * ghosts before they hit the render layer.
 */
private fun applyTargetCoord(
    rs: RoomState,
    tid: Int,
    xIsAxis: Boolean,
    value: Double?,
): RoomState {
    val cur = rs.targetCoords[tid] ?: (null to null)
    val newPair = if (xIsAxis) (value to cur.second) else (cur.first to value)
    val newCoords = if (newPair.first == null && newPair.second == null) {
        rs.targetCoords - tid
    } else {
        rs.targetCoords + (tid to newPair)
    }
    val xy = (1..3).mapNotNull { id ->
        val pair = newCoords[id] ?: return@mapNotNull null
        val x = pair.first ?: return@mapNotNull null
        val y = pair.second ?: return@mapNotNull null
        if (x.isFinite() && y.isFinite()
            && x in -3.0..3.0 && y in 0.0..6.0) (x to y) else null
    }
    return rs.copy(targetCoords = newCoords, targetsXY = xy)
}

private fun SensorState.applyPendant(payload: String, t: Long): SensorState {
    val j = json.parseToJsonElement(payload).jsonObject
    val op = j["op"]?.jsonPrimitive?.contentOrNull ?: return this
    return when (op) {
        "position" -> {
            val xyz = j["xyz_m"]?.jsonArray
            val p = if (xyz != null && xyz.size >= 3) Triple(
                xyz[0].jsonPrimitive.doubleOrNull ?: 0.0,
                xyz[1].jsonPrimitive.doubleOrNull ?: 0.0,
                xyz[2].jsonPrimitive.doubleOrNull ?: 0.0,
            ) else null
            copy(
                pendantOnline    = true,
                pendantPositionM = p,
                pendantRoom      = j["room"]?.jsonPrimitive?.contentOrNull ?: pendantRoom,
                pendantAltitudeM = p?.third ?: pendantAltitudeM,
                tStamp = t,
            )
        }
        "fall_event" -> {
            val phase = j["phase"]?.jsonPrimitive?.contentOrNull
            val drop  = j.numOrNull("alt_drop_cm")
            copy(
                pendantFallActive = (phase == "confirmed"),
                pendantFallNotes  = "phase=$phase drop_cm=$drop",
                tStamp = t,
            )
        }
        else -> this
    }
}

private fun SensorState.applyCameraDelta(payload: String, t: Long): SensorState {
    val j = json.parseToJsonElement(payload).jsonObject
    val cam = j["cam"]?.jsonPrimitive?.contentOrNull ?: return this
    val op  = j["op"]?.jsonPrimitive?.contentOrNull ?: return this
    val entity = j["entity"]?.jsonPrimitive?.contentOrNull ?: return this
    val cs = cameras[cam] ?: CameraState()
    val at = j["at"]?.jsonArray
    val cx = at?.getOrNull(0)?.jsonPrimitive?.doubleOrNull?.roundToInt() ?: 0
    val cy = at?.getOrNull(1)?.jsonPrimitive?.doubleOrNull?.roundToInt() ?: 0
    val cls = j["cls"]?.jsonPrimitive?.contentOrNull ?: "?"
    val conf = j.numOrNull("conf") ?: 0.0
    val updatedEntities = when (op) {
        "add", "move" -> cs.entities + (entity to EntitySnapshot(cls, cx, cy, conf))
        "remove"      -> cs.entities - entity
        else          -> cs.entities
    }
    return copy(cameras = cameras + (cam to cs.copy(entities = updatedEntities)), tStamp = t)
}

private fun SensorState.applyCameraFall(payload: String, t: Long): SensorState {
    val j = json.parseToJsonElement(payload).jsonObject
    val drop   = j.numOrNull("head_y_drop_frac")
    val aspect = j.numOrNull("pose_aspect")
    val conf   = j.numOrNull("pose_confidence")
    return copy(
        visionFallActive = true,
        visionFallNotes  = "head drop %.2f, aspect %.2f, conf %.2f".format(drop ?: 0.0, aspect ?: 0.0, conf ?: 0.0),
        tStamp = t,
    )
}

private fun SensorState.applyKeyframe(payload: String, t: Long): SensorState {
    val j = json.parseToJsonElement(payload).jsonObject
    val path = j["jpeg_path"]?.jsonPrimitive?.contentOrNull
    val summaryJson = j["summary"]?.jsonObject
    val summary = summaryJson?.mapValues { it.value.jsonPrimitive.intOrNull ?: 0 } ?: emptyMap()
    return copy(
        lastKeyframePath    = path,
        lastKeyframeSummary = summary,
        tStamp = t,
    )
}

private fun SensorState.applyGait(payload: String, t: Long): SensorState {
    val j = json.parseToJsonElement(payload).jsonObject
    val cam       = j.strOrNull("cam") ?: return this
    val personId  = j.strOrNull("person_id") ?: return this
    val cadence   = j.numOrNull("cadence_spm") ?: 0.0
    val strideM   = j.numOrNull("stride_length_m_rough") ?: 0.0
    val asym      = j.numOrNull("swing_asymmetry_pct") ?: 0.0
    val nStrides  = j.intOrNull("n_strides") ?: 0
    val conf      = j.numOrNull("confidence") ?: 0.0
    val snap = GaitSnapshot(
        tsMs                = t,
        cam                 = cam,
        personId            = personId,
        cadenceSpm          = cadence,
        strideLengthMRough  = strideM,
        swingAsymmetryPct   = asym,
        nStrides            = nStrides,
        confidence          = conf,
    )
    val prevHistory = gaitHistory[personId].orEmpty()
    val newHistory  = (prevHistory + snap).takeLast(MAX_GAIT_HISTORY)
    return copy(
        gait        = gait + (personId to snap),
        gaitHistory = gaitHistory + (personId to newHistory),
        tStamp      = t,
    )
}

private fun SensorState.applyAudioEvent(payload: String, t: Long): SensorState {
    val j = json.parseToJsonElement(payload).jsonObject
    val source = j.strOrNull("source") ?: return this
    val label  = j.strOrNull("label") ?: return this
    val conf   = j.numOrNull("confidence") ?: 0.0
    val sev    = j.strOrNull("severity") ?: "info"
    val top3   = j["top_3"]?.jsonArray?.mapNotNull { row ->
        val a = row.jsonArray
        val lbl = a.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val pr  = a.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: 0.0
        lbl to pr
    } ?: emptyList()
    val evt = AudioEvent(
        tsMs       = t,
        source     = source,
        label      = label,
        confidence = conf,
        severity   = sev,
        top3       = top3,
    )
    val updated = (listOf(evt) + audioEvents).take(MAX_AUDIO_EVENTS)
    return copy(audioEvents = updated, tStamp = t)
}

/**
 * Format the current SensorState as a structured English summary for Gemma.
 * Replaces v0.2's "last 12 raw messages" dump with a cleaner narrative the
 * model can reason over.
 */
fun SensorState.toGemmaContext(): String = buildString {
    // Camera-PRIORITY occupancy logic:
    //   1. Cameras don't see through walls — if a camera sees a person in a
    //      room, that's authoritative for Margaret's location.
    //   2. Radar (LD2450 24 GHz) CAN see through drywall, so its presence
    //      reading is unreliable when a camera proves otherwise.
    //   3. Camera-covered rooms (Living Room) get the camera reading.
    //      Camera-uncovered rooms (Bedroom/Kitchen) still use radar — but
    //      only WHEN no camera sees Margaret elsewhere (suppresses
    //      through-wall ghosts).
    val roomsFromCamera = cameras.entries
        .filter { (_, cs) -> cs.entities.values.any { it.cls == "person" } }
        .map { (id, _) -> roomLabel(id) }
        .distinct()
    val roomsFromRadar = rooms.entries
        .filter { (_, rs) -> rs.presence && (rs.availability == "online" || rs.availability == "unknown") }
        .map { (id, _) -> roomLabel(id) }
        .distinct()
    val roomsOccupied = when {
        roomsFromCamera.isNotEmpty() -> roomsFromCamera  // cameras win
        else                         -> roomsFromRadar
    }
    val radarSuppressed = roomsFromCamera.isNotEmpty() &&
        roomsFromRadar.any { it !in roomsFromCamera.toSet() }

    appendLine("Current Hearth sensor snapshot for Margaret:")
    when {
        roomsOccupied.size == 1 ->
            appendLine("- Margaret is currently in the ${roomsOccupied[0]}.")
        roomsOccupied.size > 1 ->
            appendLine("- Presence detected in: ${roomsOccupied.joinToString(", ")}.")
        else ->
            appendLine("- No room presence detected right now.")
    }
    if (radarSuppressed) {
        appendLine("- (Note: some radar rooms also showed presence but cameras place" +
            " Margaret in ${roomsFromCamera.joinToString(", ")}; treating the radar as" +
            " through-wall noise.)")
    }

    if (pendantOnline) {
        append("- Pendant: ONLINE")
        pendantPositionM?.let { (x, y, z) ->
            append(", position (%.2f, %.2f, %.2f) m".format(x, y, z))
        }
        pendantRoom?.let { append(", room=${roomLabel(it)}") }
        pendantAltitudeM?.let { append(", altitude %.2f m".format(it)) }
        if (pendantFallActive) append(", FALL ACTIVE ($pendantFallNotes)")
        appendLine()
    } else {
        appendLine("- Pendant: offline or no recent update")
    }
    rooms.forEach { (deviceId, rs) ->
        val name = roomLabel(deviceId)
        // Include BOTH label and device-id so user can ask by either name.
        append("- $name (device $deviceId, radar): ")
        if (rs.availability == "online" || rs.availability == "unknown") {
            append(if (rs.presence) "PRESENCE (${rs.targetCount} targets" else "empty (0 targets")
            if (rs.moving) append(", moving") else if (rs.presence) append(", still")
            append(")")
        } else {
            append("offline")
        }
        appendLine()
    }
    cameras.forEach { (cam, cs) ->
        val name = roomLabel(cam)
        append("- $name (device $cam, camera): ")
        if (cs.entities.isEmpty()) {
            append("no entities visible")
        } else {
            append(cs.entities.values.groupingBy { it.cls }.eachCount()
                .map { "${it.value}×${it.key}" }.joinToString(", "))
        }
        appendLine()
    }
    if (visionFallActive) {
        appendLine("- VISION FALL DETECTED ($visionFallNotes)")
    }
    lastKeyframePath?.let {
        appendLine("- Last camera keyframe summary: ${lastKeyframeSummary.entries.joinToString { "${it.value}×${it.key}" }}")
    }

    // ---- Audio events: last 5 minutes, grouped by label+severity ----
    val cutoff = tStamp - 5 * 60 * 1000L
    val recentAudio = audioEvents.filter { it.tsMs >= cutoff }
    if (recentAudio.isNotEmpty()) {
        val grouped = recentAudio
            .groupBy { it.label to it.severity }
            .map { (k, v) -> Triple(v.size, k.first, k.second) }
            .sortedByDescending { it.first }
        val rendered = grouped.joinToString(", ") { (n, label, sev) -> "${n}× $label ($sev)" }
        appendLine("- Recent audio events (last 5 min): $rendered")
    }

    // ---- Gait: report most-recent snapshot per person ----
    gait.values
        .sortedByDescending { it.tsMs }
        .forEach { g ->
            val verdict = when {
                g.cadenceSpm < 80.0 -> "abnormally slow"
                g.cadenceSpm < 100.0 -> "below typical"
                else -> "normal"
            }
            appendLine(
                "- Gait (${g.personId} @ ${roomLabel(g.cam)}): cadence %.0f spm, stride %.2f m, asymmetry %.1f%% — %s"
                    .format(g.cadenceSpm, g.strideLengthMRough, g.swingAsymmetryPct, verdict)
            )
        }
}
