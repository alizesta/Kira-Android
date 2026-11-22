package ai.kira.mobile.domain.model

data class ProviderRoute(
    val providerId: String,
    val modelId: String,
    val baseUrl: String,
    val enabled: Boolean,
    val priority: Int,
    val hasKey: Boolean
)
