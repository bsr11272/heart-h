package com.example.hearth.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hearth.state.AudioEvent
import com.example.hearth.state.GaitSnapshot
import com.example.hearth.state.SensorState
import com.example.hearth.state.roomLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HealthScreen(state: SensorState, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { HealthHeroCard(state) }
        if (state.gait.isNotEmpty()) {
            item {
                Text(
                    "Gait",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            items(state.gait.values.sortedByDescending { it.tsMs }) { g ->
                GaitCard(g, history = state.gaitHistory[g.personId].orEmpty())
            }
        } else {
            item { EmptyGaitCard() }
        }
        item {
            Text(
                "Audio events",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
        }
        val recent = state.audioEvents.take(15)
        if (recent.isEmpty()) {
            item { EmptyAudioCard() }
        } else {
            items(recent) { evt -> AudioEventCard(evt) }
        }
    }
}

@Composable
private fun HealthHeroCard(state: SensorState) {
    val now = System.currentTimeMillis()
    val oneHourAgo = now - 60 * 60 * 1000L
    val alertsLastHour = state.audioEvents.count {
        it.severity == "alert" && it.tsMs >= oneHourAgo
    }
    val warnsLastHour = state.audioEvents.count {
        it.severity == "warn" && it.tsMs >= oneHourAgo
    }
    val latestGait = state.gait.values.maxByOrNull { it.tsMs }
    val gaitVerdict = latestGait?.let { g ->
        when {
            g.cadenceSpm < 80.0 -> Triple("Gait abnormally slow", Color(0xFFE53935), g)
            g.cadenceSpm < 100.0 -> Triple("Gait below typical", Color(0xFFFB8C00), g)
            g.swingAsymmetryPct > 12.0 -> Triple("Gait asymmetric", Color(0xFFFB8C00), g)
            else -> Triple("Gait normal", Color(0xFF43A047), g)
        }
    }
    val (label, color, subline) = when {
        alertsLastHour > 0 -> Triple(
            "$alertsLastHour audio alert${if (alertsLastHour == 1) "" else "s"} in the last hour",
            Color(0xFFE53935),
            "Open Ask tab and check in with Margaret if not yet done.",
        )
        warnsLastHour > 0 -> Triple(
            "$warnsLastHour warning${if (warnsLastHour == 1) "" else "s"} in the last hour",
            Color(0xFFFB8C00),
            "Worth watching — not yet critical.",
        )
        gaitVerdict != null -> Triple(
            gaitVerdict.first,
            gaitVerdict.second,
            "Cadence %.0f spm · asymmetry %.1f%%".format(
                gaitVerdict.third.cadenceSpm, gaitVerdict.third.swingAsymmetryPct),
        )
        state.audioEvents.isNotEmpty() -> Triple(
            "Monitoring",
            Color(0xFF1E88E5),
            "Ambient audio + gait signals look normal.",
        )
        else -> Triple(
            "No recent health signals",
            Color(0xFF757575),
            "YamNet audio + MoveNet gait are armed — events will appear here.",
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.15f)),
    ) {
        Column(
            modifier = Modifier.padding(20.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "HEALTH & AMBIENT",
                fontSize = 11.sp,
                letterSpacing = 2.sp,
                color = color,
                fontWeight = FontWeight.Bold,
            )
            Text(
                label,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                subline,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}


/**
 * Tiny inline cadence trend chart for the gait card.
 *
 * Renders cadence (spm) over the supplied history window as a polyline,
 * plus a faint dashed band at the "normal" envelope (100-120 spm) so the
 * caregiver can read at-a-glance whether Margaret has been trending down.
 * Designed to be ~40dp tall and full width of the gait card.
 */
@Composable
private fun CadenceSparkline(
    history: List<GaitSnapshot>,
    modifier: Modifier = Modifier,
    lineColor: Color = Color(0xFF1E88E5),
) {
    if (history.size < 2) return
    val values = history.map { it.cadenceSpm.toFloat() }
    // Y-axis range: clamp to a reasonable window so noise doesn't flatten the line.
    val lo = (values.min().coerceAtMost(70f) - 5f)
    val hi = (values.max().coerceAtLeast(130f) + 5f)
    // Resolve colors in the composable scope; the Canvas drawscope below
    // isn't a @Composable context so MaterialTheme reads must happen here.
    val bandColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val midlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val yFor = { v: Float ->
            val frac = ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
            h - frac * h
        }
        // Normal band: 100-120 spm filled box (very faint).
        val yTop  = yFor(120f)
        val yBot  = yFor(100f)
        drawRect(
            color = bandColor,
            topLeft = Offset(0f, yTop),
            size = androidx.compose.ui.geometry.Size(w, (yBot - yTop).coerceAtLeast(1f)),
        )
        // Dashed midline at 110 spm — the canonical adult median.
        drawLine(
            color = midlineColor,
            start = Offset(0f, yFor(110f)),
            end   = Offset(w, yFor(110f)),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f),
        )
        // The polyline itself.
        val path = Path().apply {
            values.forEachIndexed { i, v ->
                val x = (i.toFloat() / (values.size - 1).coerceAtLeast(1)) * w
                val y = yFor(v)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(path = path, color = lineColor, style = Stroke(width = 3f))
    }
}

@Composable
private fun GaitCard(g: GaitSnapshot, history: List<GaitSnapshot> = emptyList()) {
    val cadColor = when {
        g.cadenceSpm < 80.0 -> Color(0xFFE53935)
        g.cadenceSpm < 100.0 -> Color(0xFFFB8C00)
        else -> Color(0xFF43A047)
    }
    val asymColor = when {
        g.swingAsymmetryPct > 15.0 -> Color(0xFFE53935)
        g.swingAsymmetryPct > 8.0 -> Color(0xFFFB8C00)
        else -> Color(0xFF43A047)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        g.personId,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${roomLabel(g.cam)} · ${g.nStrides} strides · conf %.2f".format(g.confidence),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                Text(
                    formatTime(g.tsMs),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetricTile("CADENCE", "%.0f spm".format(g.cadenceSpm), cadColor, Modifier.weight(1f))
                MetricTile("STRIDE", "%.2f m".format(g.strideLengthMRough),
                    Color(0xFF1E88E5), Modifier.weight(1f))
                MetricTile("ASYMMETRY", "%.1f%%".format(g.swingAsymmetryPct),
                    asymColor, Modifier.weight(1f))
            }
            if (history.size >= 2) {
                CadenceSparkline(
                    history = history,
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    lineColor = cadColor,
                )
            }
        }
    }
}

@Composable
private fun MetricTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Column {
            Text(
                label,
                fontSize = 9.sp,
                letterSpacing = 1.sp,
                color = color,
                fontWeight = FontWeight.Bold,
            )
            Text(
                value,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun EmptyGaitCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "No gait snapshots yet",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Waiting for the camera agent to detect a walking person and compute stride/cadence.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun AudioEventCard(evt: AudioEvent) {
    val color = when (evt.severity) {
        "alert" -> Color(0xFFE53935)
        "warn"  -> Color(0xFFFB8C00)
        else    -> Color(0xFF1E88E5)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        evt.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = color,
                    )
                    Text(
                        "${roomLabel(evt.source)} · ${evt.severity.uppercase()} · conf %.2f"
                            .format(evt.confidence),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
                Text(
                    formatTime(evt.tsMs),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
            if (evt.top3.isNotEmpty()) {
                Text(
                    evt.top3.joinToString("  ·  ") { (lbl, p) -> "$lbl %.2f".format(p) },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun EmptyAudioCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "No recent audio events",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "YamNet is listening on the USB-cam mics. Detections (glass, scream, doorbell, smoke alarm, …) will appear here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

private val TIME_FMT = SimpleDateFormat("HH:mm:ss", Locale.US)
private fun formatTime(ts: Long): String = TIME_FMT.format(Date(ts))
