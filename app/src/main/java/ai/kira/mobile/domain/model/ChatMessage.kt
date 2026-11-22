package ai.kira.mobile.domain.model

data class ChatAttachmentInfo(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long
)

data class ChatAttachmentInput(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray
)

data class ChatMessage(
    val id: Long,
    val role: String,
    val content: String,
    val route: String,
    val createdAt: Long,
    val attachments: List<ChatAttachmentInfo> = emptyList()
)
