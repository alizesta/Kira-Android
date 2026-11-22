package ai.kira.mobile.application.provider

import ai.kira.mobile.domain.model.ProviderConnection
import ai.kira.mobile.domain.model.ProviderDiscovery
import ai.kira.mobile.domain.repository.ProviderRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderApplicationServiceTest {
    @Test
    fun connectSelectsPreferredVerifiedFreeModelAndPersistsTrimmedKey() {
        val repository = FakeProviderRepository()
        val service = ProviderApplicationService(repository)

        val result = service.connect("  test-api-key  ")

        assertEquals("gemini", result.provider.providerId)
        assertEquals("gemini-3.8-flash", result.provider.modelId)
        assertEquals(listOf("gemini-3.8-flash", "gemini-2.5-flash"), result.availableModels)
        assertEquals("test-api-key", repository.savedApiKey)
    }

    @Test
    fun rejectsConnectWhenDiscoveryContainsNoVerifiedFreeModel() {
        val repository = FakeProviderRepository().apply {
            discovery = ProviderDiscovery("deepseek", listOf("deepseek-chat"))
        }
        val error = runCatching { ProviderApplicationService(repository).connect("key") }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(repository.savedModel.isBlank())
    }

    @Test
    fun blocksModelSelectionOutsideVerifiedDiscoveredCatalog() {
        val repository = FakeProviderRepository()
        val service = ProviderApplicationService(repository)
        service.connect("key")

        val error = runCatching { service.selectModel("gemini", "gemini-paid-preview") }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("gemini-3.8-flash", repository.savedModel)
    }

    @Test
    fun togglesAndReordersRoutesThroughRepositoryPort() {
        val repository = FakeProviderRepository().apply {
            configured = mutableListOf(
                ProviderConnection("gemini", "Google Gemini", "gemini-3.8-flash", true, 0, true),
                ProviderConnection("mistral", "Mistral", "labs-leanstral-2603", true, 1, true)
            )
        }
        val service = ProviderApplicationService(repository)

        val disabled = service.setEnabled("gemini", false)
        assertFalse(disabled.first { it.providerId == "gemini" }.enabled)

        service.move("mistral", -1)
        assertEquals(-1, repository.lastMoveDelta)
        assertEquals("mistral", repository.lastMovedProvider)
    }

    private class FakeProviderRepository : ProviderRepository {
        var configured = mutableListOf<ProviderConnection>()
        var discovery = ProviderDiscovery(
            "gemini",
            listOf("gemini-3.8-flash", "gemini-2.5-flash", "gemini-paid-preview")
        )
        var savedApiKey = ""
        var savedModel = ""
        var lastMoveDelta = 0
        var lastMovedProvider = ""

        override fun providers(): List<ProviderConnection> = configured.toList()
        override fun cachedModels(providerId: String): List<String> = discovery.modelIds
        override fun discoverProvider(apiKey: String): ProviderDiscovery = discovery
        override fun refreshModels(providerId: String): List<String> = discovery.modelIds

        override fun saveProvider(providerId: String, modelId: String, apiKey: String?) {
            savedModel = modelId
            if (apiKey != null) savedApiKey = apiKey
            val existing = configured.firstOrNull { it.providerId == providerId }
            configured.removeAll { it.providerId == providerId }
            configured.add(
                existing?.copy(modelId = modelId, hasApiKey = true)
                    ?: ProviderConnection(providerId, providerId, modelId, true, configured.size, true)
            )
        }

        override fun setEnabled(providerId: String, enabled: Boolean) {
            configured = configured.map {
                if (it.providerId == providerId) it.copy(enabled = enabled) else it
            }.toMutableList()
        }

        override fun move(providerId: String, delta: Int) {
            lastMovedProvider = providerId
            lastMoveDelta = delta
        }
    }
}
