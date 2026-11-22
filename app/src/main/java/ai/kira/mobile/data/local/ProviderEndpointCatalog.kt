package ai.kira.mobile.data.local

internal object ProviderEndpointCatalog {
    private data class Definition(val label: String, val baseUrl: String)
    private val definitions = mapOf(
        "gemini" to Definition("Google Gemini", "https://generativelanguage.googleapis.com/v1beta"),
        "openrouter" to Definition("OpenRouter", "https://openrouter.ai/api/v1"),
        "groq" to Definition("Groq", "https://api.groq.com/openai/v1"),
        "mistral" to Definition("Mistral", "https://api.mistral.ai/v1"),
        "deepseek" to Definition("DeepSeek", "https://api.deepseek.com")
    )

    fun label(providerId: String): String = definitions[providerId.trim().lowercase()]?.label ?: providerId
    fun baseUrl(providerId: String): String = definitions[providerId.trim().lowercase()]?.baseUrl
        ?: throw IllegalArgumentException("برای این Provider آدرس رسمی ثبت نشده است.")
}
