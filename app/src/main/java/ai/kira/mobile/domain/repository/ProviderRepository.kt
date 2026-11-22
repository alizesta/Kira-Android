package ai.kira.mobile.domain.repository

import ai.kira.mobile.domain.model.ProviderConnection
import ai.kira.mobile.domain.model.ProviderDiscovery

/** Persistence and provider-network port; Android-specific behavior stays in its adapter. */
interface ProviderRepository {
    fun providers(): List<ProviderConnection>
    fun cachedModels(providerId: String): List<String>
    fun discoverProvider(apiKey: String): ProviderDiscovery
    fun refreshModels(providerId: String): List<String>
    fun saveProvider(providerId: String, modelId: String, apiKey: String? = null)
    fun setEnabled(providerId: String, enabled: Boolean)
    fun move(providerId: String, delta: Int)
}
