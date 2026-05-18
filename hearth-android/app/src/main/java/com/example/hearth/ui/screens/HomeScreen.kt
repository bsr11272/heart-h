package com.example.hearth.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hearth.state.AlertFusion
import com.example.hearth.state.SensorState
import com.example.hearth.state.alertFusion
import com.example.hearth.state.alertFusionExplain
import com.example.hearth.state.roomLabel

@Composable
fun HomeScreen(
    state: SensorState,
    onQuickAsk: (String) -> Unit,
    onRoomLabelChange: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { HeroStatusCard(state) }
        item { PillRow(state) }
        item { QuickActions(onQuickAsk) }
        item { RoomLabelEditor(state, onRoomLabelChange) }
        item { LastAlertCard(state) }
    }
}

@Composable
private fun HeroStatusCard(state: SensorState) {
    val (status, color) = computeMargaretStatus(state)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.18f)),
    ) {
        Column(
            modifier = Modifier.padding(20.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "MARGARET",
                style = MaterialTheme.typography.labelLarge,
                color = color,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 2.sp,
            )
            Text(
                status,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            state.pendantPositionM?.let { (x, y, z) ->
                Text(
                    "Position: (%.1f, %.1f, %.1f) m".format(x, y, z),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
            state.pendantRoom?.let {
                Text(
                    "Room: $it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }
    }
}

private fun computeMargaretStatus(state: SensorState): Pair<String, Color> {
    // Use the three-tier fusion verdict so audio-only "unconfirmed
    // anomalous" events also surface here (not just hard pendant/vision falls).
    when (state.alertFusion()) {
        AlertFusion.CONFIRMED -> return "🚨 FALL DETECTED" to Color(0xFFE53935)
        AlertFusion.UNCONFIRMED_ANOMALOUS -> return "⚠️ HEARD SOMETHING UNUSUAL" to Color(0xFFFB8C00)
        AlertFusion.NONE -> Unit
    }
    if (!state.pendantOnline && state.rooms.values.none { it.presence }) {
        return "No signal" to Color(0xFF757575)
    }
    val activeRoom = state.rooms.entries.firstOrNull { it.value.presence }
    return when {
        activeRoom != null && activeRoom.value.moving ->
            "Moving in the ${roomLabel(activeRoom.key)}" to Color(0xFF43A047)
        activeRoom != null ->
            "Resting in the ${roomLabel(activeRoom.key)}" to Color(0xFF1E88E5)
        state.pendantOnline ->
            "Tracking pendant" to Color(0xFF1E88E5)
        else ->
            "Awaiting signal" to Color(0xFF757575)
    }
}

@Composable
private fun PillRow(state: SensorState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Subsystems",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusPill(
                "Pendant",
                if (state.pendantOnline) "online" else "offline",
                if (state.pendantOnline) Color(0xFF43A047) else Color(0xFF757575),
                Modifier.weight(1f),
            )
            StatusPill(
                "Cameras",
                "${state.cameras.size}",
                if (state.cameras.isNotEmpty()) Color(0xFF1E88E5) else Color(0xFF757575),
                Modifier.weight(1f),
            )
            StatusPill(
                "Radars",
                "${state.rooms.size}",
                if (state.rooms.isNotEmpty()) Color(0xFF8E24AA) else Color(0xFF757575),
                Modifier.weight(1f),
            )
            // YamNet audio mics. Show count of online sources (1 today; future
            // multi-cam setups will tick higher).
            val onlineAudio = state.audioAvailability.values.count { it == "online" }
            StatusPill(
                "Audio",
                if (onlineAudio > 0) "$onlineAudio on" else "off",
                if (onlineAudio > 0) Color(0xFF00897B) else Color(0xFF757575),
                Modifier.weight(1f),
            )
        }
        // Per-room pills row
        if (state.rooms.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.rooms.entries.take(3).forEach { (room, rs) ->
                    StatusPill(
                        roomLabel(room),
                        when {
                            rs.availability == "offline" -> "off"
                            rs.presence && rs.moving -> "moving"
                            rs.presence -> "present"
                            else -> "empty"
                        },
                        when {
                            rs.availability == "offline" -> Color(0xFF757575)
                            rs.presence && rs.moving -> Color(0xFF43A047)
                            rs.presence -> Color(0xFF1E88E5)
                            else -> Color(0xFF424242)
                        },
                        Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column {
            Text(
                label.uppercase(),
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                color = color,
                fontWeight = FontWeight.Bold,
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun QuickActions(onQuickAsk: (String) -> Unit) {
    val suggestions = listOf(
        "Where is Margaret right now?",
        "Did you hear anything unusual recently?",
        "How is her walking — gait normal?",
        "Summarize the last hour of activity.",
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Quick ask",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        suggestions.forEach { suggestion ->
            SuggestionChip(
                onClick = { onQuickAsk(suggestion) },
                label = { Text(suggestion) },
                colors = SuggestionChipDefaults.suggestionChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RoomLabelEditor(
    state: SensorState,
    onChange: (String, String) -> Unit,
) {
    val devices = (state.rooms.keys + state.cameras.keys)
        .distinct()
        .sortedBy { roomLabel(it) }
    if (devices.isEmpty()) return

    // Pending edits keyed by device id; populated as user types, cleared on Save/Discard.
    val edits = remember { mutableStateMapOf<String, String>() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Configure rooms",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Edit any label, then Save to apply. Empty value → reverts to default.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            // Column headers
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "SENSOR",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 10.sp,
                    modifier = Modifier.weight(0.4f),
                )
                Text(
                    "ROOM / LABEL",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 10.sp,
                    modifier = Modifier.weight(0.6f),
                )
            }
            devices.forEach { id ->
                RoomLabelRow(
                    id = id,
                    pending = edits[id],
                    onEdit = { typed -> edits[id] = typed },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        edits.forEach { (id, label) -> onChange(id, label) }
                        edits.clear()
                    },
                    enabled = edits.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        when (edits.size) {
                            0 -> "Save"
                            1 -> "Save 1 change"
                            else -> "Save ${edits.size} changes"
                        }
                    )
                }
                if (edits.isNotEmpty()) {
                    TextButton(onClick = { edits.clear() }) { Text("Discard") }
                }
            }
        }
    }
}

@Composable
private fun RoomLabelRow(
    id: String,
    pending: String?,
    onEdit: (String) -> Unit,
) {
    val persisted = roomLabel(id)
    val displayValue = pending ?: persisted
    val hasUnsaved = pending != null && pending != persisted
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            id,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            fontSize = 11.sp,
            modifier = Modifier.weight(0.4f),
        )
        OutlinedTextField(
            value = displayValue,
            onValueChange = onEdit,
            label = { Text(if (hasUnsaved) "label · unsaved" else "label") },
            singleLine = true,
            modifier = Modifier.weight(0.6f),
        )
    }
}

@Composable
private fun LastAlertCard(state: SensorState) {
    val fusion = state.alertFusion()
    if (fusion == AlertFusion.NONE) return
    val (title, container, accent) = when (fusion) {
        AlertFusion.CONFIRMED -> Triple(
            "🚨 Active fall alert",
            Color(0xFFFFCDD2), Color(0xFFB71C1C),
        )
        AlertFusion.UNCONFIRMED_ANOMALOUS -> Triple(
            "⚠️ Audio alert — awaiting confirmation",
            Color(0xFFFFE0B2), Color(0xFFE65100),
        )
        AlertFusion.NONE -> return
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = accent,
                fontWeight = FontWeight.Bold,
            )
            Text(
                state.alertFusionExplain(),
                style = MaterialTheme.typography.bodyMedium,
                color = accent,
            )
            (state.pendantFallNotes ?: state.visionFallNotes)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = accent,
                )
            }
        }
    }
}
