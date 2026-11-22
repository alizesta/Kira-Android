package ai.kira.mobile.domain.model

data class ProviderConnection(
    val providerId: String,
    val displayName: String,
    val modelId: String,
    val enabled: Boolean,
    val priority: Int,
    val hasApiKey: Boolean
)

data class ProviderDiscovery(
    val providerId: String,
    val modelIds: List<String>
)

data class ProviderOperationResult(
    val provider: ProviderConnection,
    val availableModels: List<String>
)
