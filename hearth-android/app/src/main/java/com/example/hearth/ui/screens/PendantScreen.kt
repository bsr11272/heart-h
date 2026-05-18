package com.example.hearth.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.hearth.state.SensorState

@Composable
fun PendantScreen(state: SensorState, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { PendantStatusHero(state) }
        item { PositionCard(state) }
        item { AltitudeCard(state) }
        item { FallEventCard(state) }
    }
}

@Composable
private fun PendantStatusHero(state: SensorState) {
    val (label, color) = when {
        state.pendantFallActive -> "FALL ACTIVE" to Color(0xFFE53935)
        state.pendantOnline -> "ONLINE" to Color(0xFF43A047)
        else -> "OFFLINE" to Color(0xFF757575)
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
                "POZYX PENDANT",
                fontSize = 11.sp,
                letterSpacing = 2.sp,
                color = color,
                fontWeight = FontWeight.Bold,
            )
            Text(
                label,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            state.pendantRoom?.let {
                Text(
                    "Currently in: $it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun PositionCard(state: SensorState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Position (UWB trilateration)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            val pos = state.pendantPositionM
            if (pos == null) {
                Text(
                    "no position fix yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            } else {
                val (x, y, z) = pos
                CoordRow("X (lateral)", x)
                CoordRow("Y (forward)", y)
                CoordRow("Z (height)", z)
            }
        }
    }
}

@Composable
private fun CoordRow(label: String, value: Double) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column {
            Text(label, fontSize = 11.sp, letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(
                "%.3f m".format(value),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun AltitudeCard(state: SensorState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Altitude",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            val alt = state.pendantAltitudeM
            if (alt == null) {
                Text(
                    "no altitude reading yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            } else {
                AltitudeBar(alt)
            }
        }
    }
}

@Composable
private fun AltitudeBar(altM: Double) {
    val pct = ((altM / 2.5).coerceIn(0.0, 1.0)).toFloat()
    val color = when {
        altM < 0.4 -> Color(0xFFE53935)
        altM < 0.8 -> Color(0xFFFB8C00)
        else -> Color(0xFF43A047)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "%.2f m above floor".format(altM),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.titleMedium,
            color = color,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(5.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(pct)
                    .height(10.dp)
                    .background(color, RoundedCornerShape(5.dp)),
            )
        }
        Text(
            if (altM < 0.4) "below floor threshold — possible fall"
            else if (altM < 0.8) "low — sitting / bent over"
            else "standing height",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun FallEventCard(state: SensorState) {
    if (!state.pendantFallActive) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFCDD2)),
    ) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Text(
                "🚨 Fall event active",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFB71C1C),
            )
            state.pendantFallNotes?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF7F0000),
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
