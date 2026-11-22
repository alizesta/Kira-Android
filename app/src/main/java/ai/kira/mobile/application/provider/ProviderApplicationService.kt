package ai.kira.mobile.application.provider

import ai.kira.mobile.domain.model.ProviderConnection
import ai.kira.mobile.domain.model.ProviderOperationResult
import ai.kira.mobile.domain.policy.FreeModelPolicy
import ai.kira.mobile.domain.repository.ProviderRepository

/** Provider/model use cases; no Android, HTTP or UI dependencies. */
class ProviderApplicationService(private val repository: ProviderRepository) {
    fun providers(): List<ProviderConnection> =
        repository.providers()
            .filter { it.hasApiKey && FreeModelPolicy.isVerifiedFreeChatModel(it.providerId, it.modelId) }
            .sortedWith(compareBy<ProviderConnection> { it.priority }.thenBy { it.providerId })

    fun availableModels(providerId: String): List<String> {
        val id = normalizeProviderId(providerId)
        return FreeModelPolicy.verifiedModels(id, repository.cachedModels(id))
    }

    fun connect(apiKey: String): ProviderOperationResult {
        val key = apiKey.trim()
        require(key.isNotEmpty()) { "API Key خالی است." }
        require(key.length <= MAX_API_KEY_LENGTH) { "API Key بیش از حد طولانی است." }
        require(!key.contains('\n') && !key.contains('\r')) { "API Key معتبر نیست." }
        val discovery = repository.discoverProvider(key)
        val models = FreeModelPolicy.verifiedModels(discovery.providerId, discovery.modelIds)
        require(models.isNotEmpty()) {
            "برای این Provider مدل چت رایگانِ تأییدشده پیدا نشد؛ هیچ مدل پولی ذخیره نشد."
        }
        val selected = FreeModelPolicy.recommendedModel(discovery.providerId, models)
        repository.saveProvider(discovery.providerId, selected, key)
        val connection = providers().firstOrNull { it.providerId == discovery.providerId }
            ?: throw IllegalStateException("اتصال Provider پس از ذخیره قابل بازیابی نیست.")
        return ProviderOperationResult(connection, models)
    }

    fun refresh(providerId: String): ProviderOperationResult {
        val id = normalizeProviderId(providerId)
        val current = providers().firstOrNull { it.providerId == id }
            ?: throw IllegalStateException("اتصال Provider پیدا نشد یا API Key آن موجود نیست.")
        val models = FreeModelPolicy.verifiedModels(id, repository.refreshModels(id))
        require(models.isNotEmpty()) { "مدل چت رایگان قابل تأییدی دریافت نشد؛ تنظیم قبلی حفظ شد." }
        val selected = current.modelId.takeIf { it in models }
            ?: FreeModelPolicy.recommendedModel(id, models)
        repository.saveProvider(id, selected)
        val updated = providers().firstOrNull { it.providerId == id }
            ?: throw IllegalStateException("تنظیم Provider پس از تازه‌سازی قابل بازیابی نیست.")
        return ProviderOperationResult(updated, models)
    }

    fun selectModel(providerId: String, modelId: String): ProviderOperationResult {
        val id = normalizeProviderId(providerId)
        val model = modelId.trim()
        val available = availableModels(id)
        require(model in available && FreeModelPolicy.isVerifiedFreeChatModel(id, model)) {
            "این مدل در فهرست رایگان تأییدشده این Provider وجود ندارد."
        }
        repository.saveProvider(id, model)
        val updated = providers().firstOrNull { it.providerId == id }
            ?: throw IllegalStateException("تنظیم Provider پس از انتخاب مدل قابل بازیابی نیست.")
        return ProviderOperationResult(updated, available)
    }

    fun setEnabled(providerId: String, enabled: Boolean): List<ProviderConnection> {
        val id = requireConfiguredProvider(providerId)
        repository.setEnabled(id, enabled)
        return providers()
    }

    fun move(providerId: String, delta: Int): List<ProviderConnection> {
        require(delta == -1 || delta == 1) { "جابجایی فقط یک جایگاه مجاز است." }
        val id = requireConfiguredProvider(providerId)
        repository.move(id, delta)
        return providers()
    }

    private fun requireConfiguredProvider(providerId: String): String {
        val id = normalizeProviderId(providerId)
        require(providers().any { it.providerId == id }) { "اتصال Provider پیدا نشد." }
        return id
    }

    private fun normalizeProviderId(providerId: String): String {
        val id = providerId.trim().lowercase()
        require(id.matches(Regex("[a-z0-9][a-z0-9_-]{1,39}"))) { "شناسه Provider معتبر نیست." }
        return id
    }

    private companion object {
        const val MAX_API_KEY_LENGTH = 512
    }
}
