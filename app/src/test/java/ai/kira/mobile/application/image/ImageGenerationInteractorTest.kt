package ai.kira.mobile.application.image

import ai.kira.mobile.domain.model.GeneratedImage
import ai.kira.mobile.domain.model.ImageProviderSettings
import ai.kira.mobile.domain.repository.ImageGenerationRepository
import ai.kira.mobile.domain.repository.ImageGenerationSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationInteractorTest {
    @Test
    fun trimsPromptAndPassesValidatedSizeToRepository() {
        val repository = FakeImageRepository()
        val service = ImageGenerationInteractor(repository, FakeSettingsRepository())
        val image = service.generate("  a blue fox  ", "1024x1024")

        assertEquals("a blue fox", repository.prompt)
        assertEquals("1024x1024", repository.size)
        assertEquals("test-image", image.id)
    }

    @Test
    fun validatesAndPersistsProviderSettingsWithoutExposingTheSecret() {
        val settings = FakeSettingsRepository()
        val service = ImageGenerationInteractor(FakeImageRepository(), settings)
        service.saveSettings("https://images.example/v1/", "image-model", " secret ")

        assertTrue(service.hasSettings())
        assertEquals(ImageProviderSettings("https://images.example/v1", "image-model"), service.settings())
        assertEquals("secret", settings.savedKey)
    }

    @Test
    fun clearingSettingsRemovesProviderConfiguration() {
        val settings = FakeSettingsRepository()
        val service = ImageGenerationInteractor(FakeImageRepository(), settings)
        service.saveSettings("https://images.example/v1", "image-model", "secret")
        service.clearSettings()
        assertFalse(service.hasSettings())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPlainHttpProviderBeforePersistingCredentials() {
        ImageGenerationInteractor(FakeImageRepository(), FakeSettingsRepository())
            .saveSettings("http://images.example/v1", "image-model", "secret")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsShortPromptBeforeNetworkCall() {
        ImageGenerationInteractor(FakeImageRepository(), FakeSettingsRepository())
            .generate("  ", "1024x1024")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedSize() {
        ImageGenerationInteractor(FakeImageRepository(), FakeSettingsRepository())
            .generate("a blue fox", "64x64")
    }

    @Test(expected = IllegalStateException::class)
    fun requiresSavedProviderBeforeGeneration() {
        ImageGenerationInteractor(FakeImageRepository(), FakeSettingsRepository(false))
            .generate("a blue fox", "1024x1024")
    }

    private class FakeImageRepository : ImageGenerationRepository {
        var prompt = ""
        var size = ""

        override fun generate(prompt: String, size: String): GeneratedImage {
            this.prompt = prompt
            this.size = size
            return GeneratedImage("test-image", prompt, "test-model", "/tmp/image.png", 1L)
        }

        override fun history(limit: Int): List<GeneratedImage> = emptyList()
    }

    private class FakeSettingsRepository(initiallyConfigured: Boolean = true) : ImageGenerationSettingsRepository {
        private var configured = initiallyConfigured
        var savedKey = ""
        private var savedSettings = ImageProviderSettings("https://images.example/v1", "image-model")

        override fun hasSettings(): Boolean = configured
        override fun settings(): ImageProviderSettings = savedSettings
        override fun save(baseUrl: String, model: String, apiKey: String) {
            savedSettings = ImageProviderSettings(baseUrl, model)
            savedKey = apiKey
            configured = true
        }
        override fun readApiKey(): String = savedKey
        override fun clear() {
            configured = false
            savedKey = ""
        }
    }
}
