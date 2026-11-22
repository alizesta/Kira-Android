package ai.kira.mobile.domain.repository

import ai.kira.mobile.domain.model.GeneratedImage
import ai.kira.mobile.domain.model.ImageProviderSettings

interface ImageGenerationRepository {
    fun generate(prompt: String, size: String): GeneratedImage
    fun history(limit: Int = 40): List<GeneratedImage>
}

/**
 * Device-local settings/credential port. The API key is read only by the data
 * adapter that makes the provider request; presentation never receives it.
 */
interface ImageGenerationSettingsRepository {
    fun hasSettings(): Boolean
    fun settings(): ImageProviderSettings
    fun save(baseUrl: String, model: String, apiKey: String)
    fun readApiKey(): String
    fun clear()
}
