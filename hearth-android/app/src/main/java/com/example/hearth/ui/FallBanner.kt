package com.example.hearth.ui

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hearth.state.AlertFusion
import com.example.hearth.state.SensorState
import com.example.hearth.state.alertFusion
import com.example.hearth.state.alertFusionExplain
import kotlinx.coroutines.delay

@Composable
fun FallBanner(
    state: SensorState,
    dismissed: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onCheckIn: (() -> Unit)? = null,
) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.tStamp) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val fusion = state.alertFusion(now)
    if (dismissed || fusion == AlertFusion.NONE) {
        Spacer(Modifier.height(0.dp))
        return
    }

    val isConfirmed = fusion == AlertFusion.CONFIRMED
    val transition = rememberInfiniteTransition(label = "fall-banner-pulse")
    val bg by transition.animateColor(
        initialValue = if (isConfirmed) Color(0xFFD32F2F) else Color(0xFFFB8C00),
        targetValue  = if (isConfirmed) Color(0xFFB71C1C) else Color(0xFFE65100),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "fall-banner-color",
    )

    val explain = state.alertFusionExplain(now)
    val title = if (isConfirmed) "🚨 FALL DETECTED" else "⚠️ HEARD SOMETHING UNUSUAL"
    val subline = if (isConfirmed) "Source: $explain" else explain
    val secondsAgo = ((now - state.tStamp).coerceAtLeast(0L) / 1000L)
    val timeLine = when {
        state.tStamp <= 0L -> "just now"
        secondsAgo < 1L    -> "just now"
        secondsAgo == 1L   -> "1 second ago"
        secondsAgo < 60L   -> "$secondsAgo seconds ago"
        else               -> "${secondsAgo / 60L} min ${secondsAgo % 60L} s ago"
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(bg, RoundedCornerShape(10.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = if (isConfirmed) 22.sp else 18.sp,
            )
            if (subline.isNotBlank()) {
                Text(
                    text = subline,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
            }
            if (!isConfirmed) {
                Text(
                    text = "Tap \"Check in\" to ask Margaret if she's OK.",
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = 13.sp,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = timeLine,
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                if (!isConfirmed && onCheckIn != null) {
                    OutlinedButton(
                        onClick = onCheckIn,
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Text("Check in", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
                OutlinedButton(onClick = onDismiss) {
                    Text("Dismiss", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
