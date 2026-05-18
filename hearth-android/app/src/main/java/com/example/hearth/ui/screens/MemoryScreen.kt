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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hearth.memory.MemoryQueryClient
import com.example.hearth.memory.MemoryQueryRequest
import com.example.hearth.memory.MemoryQueryResponse
import com.example.hearth.state.SensorState
import com.example.hearth.state.toGemmaContext
import kotlinx.coroutines.launch

/**
 * Debug-style transparency tab — shows EXACTLY what Gemma sees when you ask
 * a question:
 *   • The current snapshot (toGemmaContext output)
 *   • The recent-activity memory query response (timeline of events)
 *
 * Stops the "is the database good?" question — if the data looks wrong here,
 * it's a sensor/data problem; if it looks right but Gemma answers wrong,
 * it's a model-prompting problem.
 */
@Composable
fun MemoryScreen(
    state: SensorState,
    memoryClient: MemoryQueryClient,
    modifier: Modifier = Modifier,
) {
    var memoryText by remember { mutableStateOf<String?>(null) }
    var lastQueryAt by remember { mutableStateOf<Long?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        if (isLoading) return
        isLoading = true
        scope.launch {
            val resp = runCatching {
                memoryClient.query(
                    MemoryQueryRequest.RecentDeltas(src = null, sinceMin = 15, limit = 200)
                )
            }.getOrNull()
            memoryText = renderResponseSummary(resp)
            lastQueryAt = System.currentTimeMillis()
            isLoading = false
        }
    }

    // Auto-fetch once when the tab opens.
    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Memory transparency",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "What Gemma actually sees when you ask a question.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )

        // ----- SNAPSHOT -----
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                Text(
                    "RIGHT-NOW SNAPSHOT",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.sp,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            RoundedCornerShape(6.dp),
                        )
                        .padding(8.dp),
                ) {
                    Text(
                        text = state.toGemmaContext(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        // ----- RECENT MEMORY (KuzuDB graph) -----
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "RECENT MEMORY (last 15 min)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 1.sp,
                        )
                        lastQueryAt?.let {
                            val ago = ((System.currentTimeMillis() - it) / 1000).toInt()
                            Text(
                                "fetched ${ago}s ago",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                fontSize = 10.sp,
                            )
                        }
                    }
                    Button(onClick = { refresh() }, enabled = !isLoading) {
                        Text(if (isLoading) "…" else "Refresh")
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            RoundedCornerShape(6.dp),
                        )
                        .padding(8.dp),
                ) {
                    Text(
                        text = memoryText ?: if (isLoading) "querying KuzuDB…"
                                            else "tap Refresh to query the memory graph",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

private fun renderResponseSummary(resp: MemoryQueryResponse?): String {
    if (resp == null) return "query failed or timed out (no response from Coral)"
    if (!resp.ok) return "query error: ${resp.error}"
    val rows = resp.rows.orEmpty()
    if (rows.isEmpty()) return "no rows in window"
    return buildString {
        appendLine("${rows.size} deltas returned. Latest 15:")
        appendLine()
        rows.take(15).forEach { row ->
            val ts = row["d.ts"]?.toString()?.removeSurrounding("\"") ?: "?"
            val src = row["d.src"]?.toString()?.removeSurrounding("\"") ?: "?"
            val op = row["d.op"]?.toString()?.removeSurrounding("\"") ?: "?"
            val topic = row["d.topic"]?.toString()?.removeSurrounding("\"") ?: "?"
            val payload = (row["d.payload"]?.toString() ?: "").take(60)
                .removeSurrounding("\"")
            appendLine("$ts  $src/$op")
            appendLine("    $topic")
            if (payload.isNotBlank()) appendLine("    $payload")
        }
    }.trimEnd()
}
