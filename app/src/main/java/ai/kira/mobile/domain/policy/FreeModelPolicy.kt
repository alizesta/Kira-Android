package ai.kira.mobile.domain.policy

import ai.kira.mobile.domain.model.ProviderRoute

/** Pure policy for verified free models and deterministic provider failover. */
object FreeModelPolicy {
    fun isVerifiedFreeChatModel(providerId: String, modelId: String): Boolean {
        val provider = providerId.trim().lowercase()
        val model = modelId.trim()
        if (model.isEmpty()) return false
        return when (provider) {
            "gemini" -> model in setOf("gemini-3.8-flash", "gemini-2.5-flash", "gemini-2.5-flash-lite")
            "openrouter" -> model.endsWith(":free")
            "mistral" -> model == "labs-leanstral-2603"
            else -> false
        }
    }

    fun verifiedModels(providerId: String, models: List<String>): List<String> =
        models.map { it.trim() }
            .filter { it.isNotEmpty() && isVerifiedFreeChatModel(providerId, it) }
            .distinct()

    fun recommendedModel(providerId: String, models: List<String>): String {
        val verified = verifiedModels(providerId, models)
        require(verified.isNotEmpty()) { "هیچ مدل چت رایگان تأییدشده‌ای برای این Provider وجود ندارد." }
        val preferred = when (providerId.trim().lowercase()) {
            "gemini" -> listOf("gemini-3.8-flash", "gemini-2.5-flash", "gemini-2.5-flash-lite")
            "mistral" -> listOf("labs-leanstral-2603")
            "openrouter" -> listOf(
                "nvidia/nemotron-3-ultra:free",
                "minimax/minimax-m3:free",
                "qwen/qwen3.5-32b:free",
                "google/gemma-4-26b-a4b-it:free"
            )
            else -> emptyList()
        }
        return preferred.firstOrNull { it in verified } ?: verified.first()
    }

    fun selectRoutes(routes: List<ProviderRoute>): List<ProviderRoute> =
        routes.asSequence()
            .filter { it.enabled && it.hasKey && isVerifiedFreeChatModel(it.providerId, it.modelId) }
            .sortedWith(compareBy<ProviderRoute> { it.priority }.thenBy { it.providerId })
            .toList()
}
