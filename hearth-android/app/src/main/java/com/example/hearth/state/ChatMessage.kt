package com.example.hearth.state

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val role: Role,
    val text: String,
    val tsMs: Long = 0L,  // default 0 — ViewModel assigns System.currentTimeMillis() when creating
) {
    @Serializable
    enum class Role { USER, ASSISTANT, SYSTEM }

    companion object {
        fun now(role: Role, text: String): ChatMessage =
            ChatMessage(role, text, System.currentTimeMillis())
    }
}
