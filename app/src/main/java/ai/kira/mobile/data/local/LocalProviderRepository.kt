package ai.kira.mobile.data.local

import ai.kira.mobile.domain.model.ProviderConnection
import ai.kira.mobile.domain.model.ProviderDiscovery
import ai.kira.mobile.domain.repository.ProviderRepository
import ai.kira.mobile.data.remote.ProviderDiscoveryRemoteDataSource

/** SQLite/Android adapter for the provider repository port. */
class LocalProviderRepository(
    private val core: SqliteKiraStore,
    private val discovery: ProviderDiscoveryRemoteDataSource
) : ProviderRepository {
    override fun providers(): List<ProviderConnection> =
        core.providers().map { local ->
            ProviderConnection(
                providerId = local.id,
                displayName = ProviderEndpointCatalog.label(local.id),
                modelId = local.model,
                enabled = local.enabled,
                priority = local.priority,
                hasApiKey = local.hasKey
            )
        }

    override fun cachedModels(providerId: String): List<String> = core.discoveredModels(providerId)

    override fun discoverProvider(apiKey: String): ProviderDiscovery {
        val discovered = discovery.detectProviderAndDiscoverModels(apiKey)
        val cached = core.cacheDiscoveredModels(discovered.providerId, discovered.modelIds)
        return ProviderDiscovery(discovered.providerId, cached)
    }

    override fun refreshModels(providerId: String): List<String> {
        val models = discovery.discoverModels(providerId, core.providerApiKey(providerId))
        return core.cacheDiscoveredModels(providerId, models)
    }

    override fun saveProvider(providerId: String, modelId: String, apiKey: String?) {
        val existing = core.providers().firstOrNull { it.id == providerId }
        val endpoint = existing?.baseUrl ?: ProviderEndpointCatalog.baseUrl(providerId)
        core.saveProvider(
            id = providerId,
            model = modelId,
            baseUrl = endpoint,
            apiKey = apiKey.orEmpty(),
            enabled = existing?.enabled ?: true
        )
    }

    override fun setEnabled(providerId: String, enabled: Boolean) = core.setProviderEnabled(providerId, enabled)
    override fun move(providerId: String, delta: Int) = core.moveProvider(providerId, delta)
}
