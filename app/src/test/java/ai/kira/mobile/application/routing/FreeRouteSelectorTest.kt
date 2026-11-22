package ai.kira.mobile.application.routing

import ai.kira.mobile.domain.model.ProviderRoute
import org.junit.Assert.assertEquals
import org.junit.Test

class FreeRouteSelectorTest {
    @Test
    fun selectsOnlyEnabledRoutesWithKeysAndVerifiedFreeModels() {
        val routes = listOf(
            route("gemini", "gemini-3.8-flash", priority = 2),
            route("openrouter", "vendor/model:free", priority = 1),
            route("mistral", "labs-leanstral-2603", priority = 3),
            route("openrouter", "vendor/model", priority = 0),
            route("gemini", "gemini-3.8-flash", priority = 0, enabled = false),
            route("mistral", "labs-leanstral-2603", priority = 0, hasKey = false),
            route("unknown", "some-model:free", priority = 0)
        )

        val selected = FreeRouteSelector.select(routes)

        assertEquals(
            listOf("openrouter", "gemini", "mistral"),
            selected.map { it.providerId }
        )
    }

    @Test
    fun ordersByPriorityAndUsesProviderIdAsStableTieBreaker() {
        val routes = listOf(
            route("mistral", "labs-leanstral-2603", priority = 1),
            route("gemini", "gemini-3.8-flash", priority = 1),
            route("openrouter", "vendor/model:free", priority = 0)
        )

        assertEquals(
            listOf("openrouter", "gemini", "mistral"),
            FreeRouteSelector.select(routes).map { it.providerId }
        )
    }

    private fun route(
        providerId: String,
        modelId: String,
        priority: Int,
        enabled: Boolean = true,
        hasKey: Boolean = true
    ) = ProviderRoute(
        providerId = providerId,
        modelId = modelId,
        baseUrl = "https://provider.example",
        enabled = enabled,
        priority = priority,
        hasKey = hasKey
    )
}
