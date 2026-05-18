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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hearth.llm.EngineState
import com.example.hearth.state.ChatMessage

@Composable
fun AskScreen(
    chatHistory: List<ChatMessage>,
    chatInput: String,
    onChatInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onClearChat: () -> Unit,
    inferenceInFlight: Boolean,
    engine: EngineState,
    onLoadModel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                EngineStatusBar(engine = engine, onLoadModel = onLoadModel)
            }
            if (chatHistory.isNotEmpty()) {
                androidx.compose.material3.TextButton(
                    onClick = onClearChat,
                    enabled = !inferenceInFlight,
                ) {
                    androidx.compose.material3.Text("Clear")
                }
            }
        }

        ChatTranscript(
            chatHistory = chatHistory,
            inferenceInFlight = inferenceInFlight,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        if (chatHistory.isEmpty()) {
            SuggestedPromptStrip(onPick = onChatInputChange)
        }

        InputRow(
            value = chatInput,
            onValueChange = onChatInputChange,
            onSend = onSend,
            enabled = engine is EngineState.Ready && !inferenceInFlight,
        )
    }
}

@Composable
private fun EngineStatusBar(engine: EngineState, onLoadModel: () -> Unit) {
    val (label, color) = when (engine) {
        is EngineState.Idle    -> "Model not loaded" to Color(0xFF757575)
        is EngineState.Loading -> "Loading model…"  to Color(0xFFFB8C00)
        is EngineState.Ready   -> "Gemma 4 E2B ready" to Color(0xFF43A047)
        is EngineState.Error   -> "Model error: ${engine.message}" to Color(0xFFE53935)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (engine !is EngineState.Ready) {
            Button(onClick = onLoadModel, enabled = engine !is EngineState.Loading) {
                Text(if (engine is EngineState.Error) "Retry" else "Load")
            }
        }
    }
}

@Composable
private fun ChatTranscript(
    chatHistory: List<ChatMessage>,
    inferenceInFlight: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(chatHistory.size, inferenceInFlight) {
        val target = chatHistory.size - 1 + if (inferenceInFlight) 1 else 0
        if (target >= 0) listState.animateScrollToItem(target.coerceAtMost(chatHistory.size))
    }
    if (chatHistory.isEmpty() && !inferenceInFlight) {
        EmptyChatHint(modifier = modifier)
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(chatHistory) { msg -> Bubble(msg) }
        if (inferenceInFlight) item { ThinkingBubble() }
    }
}

@Composable
private fun EmptyChatHint(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("💬", fontSize = 48.sp)
            Text(
                "Ask Hearth about Margaret",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Gemma 4 runs entirely on this phone",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun Bubble(msg: ChatMessage) {
    val isUser = msg.role == ChatMessage.Role.USER
    val bg = if (isUser) MaterialTheme.colorScheme.primary
             else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (isUser) MaterialTheme.colorScheme.onPrimary
             else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = if (isUser) RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
                else RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(bg, shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(msg.text, color = fg, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ThinkingBubble() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                "thinking…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SuggestedPromptStrip(onPick: (String) -> Unit) {
    val suggestions = listOf(
        "Where is Margaret right now?",
        "Is anyone in the kitchen?",
        "Summarize the last hour",
        "What happened in the bathroom today?",
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Suggested",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        suggestions.forEach { s ->
            SuggestionChip(
                onClick = { onPick(s) },
                label = { Text(s) },
                colors = SuggestionChipDefaults.suggestionChipColors(),
            )
        }
    }
}

@Composable
private fun InputRow(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("Ask Hearth…") },
            modifier = Modifier.weight(1f),
            maxLines = 3,
            enabled = enabled,
        )
        Button(
            onClick = onSend,
            enabled = enabled && value.isNotBlank(),
        ) {
            Text("Send")
        }
    }
}
