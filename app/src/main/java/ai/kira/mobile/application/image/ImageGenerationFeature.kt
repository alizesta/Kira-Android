package ai.kira.mobile.application.image

import ai.kira.mobile.domain.model.GeneratedImage
import ai.kira.mobile.domain.model.ImageProviderSettings
import ai.kira.mobile.domain.repository.ImageGenerationRepository
import ai.kira.mobile.domain.repository.ImageGenerationSettingsRepository
import java.net.URI

/**
 * Image generation use cases: settings validation, prompt policy and history.
 * Android storage, media APIs and HTTP stay behind repository ports.
 */
class ImageGenerationInteractor(
    private val repository: ImageGenerationRepository,
    private val settingsRepository: ImageGenerationSettingsRepository
) {
    fun hasSettings(): Boolean = settingsRepository.hasSettings()

    fun settings(): ImageProviderSettings = settingsRepository.settings()

    fun saveSettings(baseUrl: String, model: String, apiKey: String) {
        val normalizedUrl = baseUrl.trim().trimEnd('/')
        val uri = runCatching { URI(normalizedUrl) }
            .getOrElse { throw IllegalArgumentException("آدرس Provider تصویر معتبر نیست.", it) }
        require(uri.scheme.equals("https", ignoreCase = true)) { "آدرس Provider تصویر باید HTTPS باشد." }
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "آدرس Provider تصویر معتبر نیست."
        }

        val normalizedModel = model.trim()
        require(normalizedModel.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}"))) {
            "شناسه مدل تصویر معتبر نیست."
        }

        val key = apiKey.trim()
        require(key.isNotEmpty() && key.length <= MAX_API_KEY_LENGTH) { "کلید Provider تصویر معتبر نیست." }
        require(!key.contains('\n') && !key.contains('\r')) { "کلید Provider تصویر معتبر نیست." }

        settingsRepository.save(normalizedUrl, normalizedModel, key)
    }

    fun clearSettings() = settingsRepository.clear()

    fun generate(prompt: String, size: String): GeneratedImage {
        val normalizedPrompt = prompt.trim()
        require(normalizedPrompt.length >= MIN_PROMPT_LENGTH) { "توصیف تصویر خیلی کوتاه است." }
        require(normalizedPrompt.length <= MAX_PROMPT_LENGTH) { "توصیف تصویر بیش از حد طولانی است." }
        require(size in SUPPORTED_SIZES) { "ابعاد تصویر پشتیبانی نمی‌شود." }
        check(settingsRepository.hasSettings()) { "ابتدا Provider تصویر را تنظیم کن." }
        return repository.generate(normalizedPrompt, size)
    }

    fun history(limit: Int = DEFAULT_HISTORY_LIMIT): List<GeneratedImage> {
        require(limit in 1..MAX_HISTORY_LIMIT) { "تعداد تصاویر تاریخچه معتبر نیست." }
        return repository.history(limit)
    }

    private companion object {
        const val MAX_API_KEY_LENGTH = 512
        const val MIN_PROMPT_LENGTH = 3
        const val MAX_PROMPT_LENGTH = 2_000
        const val DEFAULT_HISTORY_LIMIT = 40
        const val MAX_HISTORY_LIMIT = 100
        val SUPPORTED_SIZES = setOf("1024x1024", "1024x1536", "1536x1024")
    }
}
