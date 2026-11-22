package ai.kira.mobile.domain.model

data class ChatMessage(
    val id: Long,
    val role: String,
    val content: String,
    val route: String,
    val createdAt: Long
)
