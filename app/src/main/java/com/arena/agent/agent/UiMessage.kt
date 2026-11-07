package com.arena.agent.agent

enum class Role {
    User,
    Assistant,
    Status,
}

data class UiMessage(
    val id: Long,
    val role: Role,
    val text: String,
    val summary: String? = null,
    val detail: String? = null,
    val streaming: Boolean = false,
    val status: WorkStatus? = null,
)
