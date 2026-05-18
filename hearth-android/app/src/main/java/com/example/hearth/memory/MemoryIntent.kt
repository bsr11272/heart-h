package com.example.hearth.memory

/**
 * Heuristic intent classifier — decides whether a chat prompt needs the
 * historical KuzuDB graph or just the current SensorState snapshot.
 *
 * Examples:
 *   "is Margaret in the kitchen?"         -> needsMemory=false
 *   "where was Margaret an hour ago?"     -> needsMemory=true, window=60, level=2
 *   "what happened this morning?"         -> needsMemory=true, window=720, level=2
 *   "summarize yesterday's activity"      -> needsMemory=true, window=1440, level=3
 *   "what did the camera see just now?"   -> needsMemory=true, window=15, level=1, src=camera
 *   "did she fall last week?"             -> needsMemory=true, window=10080, level=3
 */
data class MemoryIntent(
    val needsMemory: Boolean,
    val timeWindowMin: Int,
    val preferredLevel: Int,   // 0=raw deltas, 1=5min, 2=hourly, 3=daily
    val srcHint: String?,      // "camera" / "radar" / "pendant" / null=any
)

private val RECENT_PHRASES = listOf(
    "just now", "right now", "recently", "moment ago", "moments ago",
    "last few minutes", "past few minutes", "last 5 min", "last 10 min",
    "last 15 min", "in the last minute",
)
private val HOUR_PHRASES = listOf(
    "last hour", "this hour", "an hour ago", "past hour", "hour ago",
    "last 30 min", "last 60 min", "half hour",
)
private val MORNING_PHRASES = listOf(
    "this morning", "this afternoon", "this evening", "today",
    "earlier today", "so far today",
)
private val DAY_PHRASES = listOf(
    "yesterday", "last night", "last week", "past week", "last 7 days",
    "this week", "past day", "last 24 hours",
)
private val RETROSPECTIVE_VERBS = listOf(
    "what happened", "what did", "when was", "when did",
    "summarize", "summary", "did she", "did he", "did margaret",
    "history", "earlier", "previous", "before",
)
private val SRC_HINTS = mapOf(
    "camera" to listOf("camera", "saw", "see", "video", "vision"),
    "radar"  to listOf("radar", "presence", "room", "kitchen", "living room", "bedroom"),
    "pendant" to listOf("pendant", "position", "fall", "altitude"),
)

fun classifyMemoryIntent(prompt: String): MemoryIntent {
    val p = prompt.lowercase()

    val src = SRC_HINTS.entries.firstOrNull { (_, words) ->
        words.any { it in p }
    }?.key

    when {
        DAY_PHRASES.any { it in p } -> {
            val window = if ("yesterday" in p || "24 hours" in p) 1440
                else if ("week" in p || "7 days" in p) 10080
                else 1440
            return MemoryIntent(true, window, 3, src)
        }
        MORNING_PHRASES.any { it in p } -> {
            return MemoryIntent(true, 720, 2, src)
        }
        HOUR_PHRASES.any { it in p } -> {
            return MemoryIntent(true, 60, 2, src)
        }
        RECENT_PHRASES.any { it in p } -> {
            return MemoryIntent(true, 15, 1, src)
        }
        RETROSPECTIVE_VERBS.any { it in p } -> {
            return MemoryIntent(true, 60, 2, src)
        }
    }

    return MemoryIntent(false, 0, 0, src)
}
