package ai.kira.mobile.domain.model

data class ChatResult(
    val content: String,
    val provider: String,
    val model: String
)
