package ai.kira.mobile.domain.model

data class GeneratedImage(
    val id: String,
    val prompt: String,
    val model: String,
    val localPath: String,
    val createdAt: Long
)

data class ImageProviderSettings(
    val baseUrl: String,
    val model: String
)
