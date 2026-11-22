package ai.kira.mobile.data.remote

import ai.kira.mobile.domain.model.ProviderDiscovery
import ai.kira.mobile.domain.policy.FreeModelPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** Remote HTTP adapter for provider identification and free-chat model catalogs. */
class ProviderDiscoveryRemoteDataSource {
    fun detectProviderAndDiscoverModels(apiKey:String):ProviderDiscovery {
        val key=apiKey.trim()
        require(key.isNotBlank()) { "API Key خالی است." }
        val candidates=when {
            key.startsWith("AIza") -> listOf("gemini")
            key.startsWith("gsk_") -> listOf("groq")
            key.startsWith("sk-or-") -> listOf("openrouter")
            else -> listOf("mistral","deepseek","openrouter","groq","gemini")
        }
        val failures=mutableListOf<String>()
        for(provider in candidates) {
            try { return ProviderDiscovery(provider,discoverModels(provider,key)) }
            catch(error:Exception) { failures += provider+": "+(error.message ?: "request failed").take(100) }
        }
        throw IllegalStateException("نوع API Key شناسایی نشد یا دسترسی مدل‌ها رد شد. "+failures.joinToString(" | "))
    }

    fun discoverModels(providerId:String,apiKey:String):List<String> {
        require(apiKey.isNotBlank()) { "API Key خالی است." }
        val id=providerId.trim().lowercase()
        val endpoint=when(id) {
            "gemini" -> "https://generativelanguage.googleapis.com/v1beta/models"
            "groq" -> "https://api.groq.com/openai/v1/models"
            "mistral" -> "https://api.mistral.ai/v1/models"
            "deepseek" -> "https://api.deepseek.com/models"
            "openrouter" -> "https://openrouter.ai/api/v1/models?sort=most-popular"
            else -> throw IllegalArgumentException("این Provider هنوز فهرست خودکار مدل‌ها را پشتیبانی نمی‌کند.")
        }
        val connection=(URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod="GET"; connectTimeout=15_000; readTimeout=30_000
            setRequestProperty("Accept","application/json")
            if(id=="gemini") setRequestProperty("x-goog-api-key",apiKey)
            else setRequestProperty("Authorization","Bearer "+apiKey)
        }
        val models=try {
            val status=connection.responseCode
            val raw=readBounded(if(status in 200..299) connection.inputStream else connection.errorStream)
            if(status !in 200..299) {
                val detail=runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
                throw IllegalStateException("دریافت مدل‌ها ناموفق بود: HTTP "+status+" "+detail.take(220))
            }
            val root=JSONObject(raw)
            val items=root.optJSONArray(if(id=="gemini") "models" else "data") ?: JSONArray()
            val found=mutableListOf<String>()
            for(index in 0 until items.length()) {
                val item=items.optJSONObject(index) ?: continue
                val name=item.optString("id",item.optString("name")).removePrefix("models/")
                if(name.isBlank() || !supportsChat(id,item)) continue
                val pricing=item.optJSONObject("pricing")
                val openRouterIsFree=pricing!=null &&
                    pricing.optString("prompt").toDoubleOrNull()==0.0 &&
                    pricing.optString("completion").toDoubleOrNull()==0.0
                val free=when(id) {
                    "gemini","mistral" -> FreeModelPolicy.isVerifiedFreeChatModel(id,name)
                    "openrouter" -> name.endsWith(":free") && openRouterIsFree
                    else -> false
                }
                if(free) found += name
            }
            val distinct=found.distinct()
            if(id=="openrouter") distinct else distinct.sorted()
        } finally { connection.disconnect() }
        if(models.isEmpty()) {
            val message=when(id) {
                "deepseek" -> "DeepSeek API مدل رایگان دائمی ندارد؛ کلید آن برای مدل‌های پولی است و در فهرست رایگان Kira نمایش داده نمی‌شود."
                "groq" -> "فهرست فعلی Groq مدل چت رایگانِ با قیمت صفر مشخص نمی‌کند؛ برای جلوگیری از هزینه، هیچ مدلی اضافه نشد."
                "mistral" -> "در فهرست فعلی Mistral مدل چت رایگانِ سازگار پیدا نشد. فقط مدل‌های رایگان دارای قابلیت چت پذیرفته می‌شوند."
                else -> "هیچ مدل چت رایگانی با قیمت صفر برای این Provider پیدا نشد. هیچ مدل پولی اضافه یا انتخاب نشد."
            }
            throw IllegalStateException(message)
        }
        return models
    }

    private fun supportsChat(provider:String,item:JSONObject):Boolean = when(provider) {
        "gemini" -> {
            val methods=item.optJSONArray("supportedGenerationMethods") ?: JSONArray()
            (0 until methods.length()).any { methods.optString(it)=="generateContent" }
        }
        "mistral" -> item.optJSONObject("capabilities")?.optBoolean("completion_chat",false)==true
        "openrouter" -> {
            val modalities=item.optJSONObject("architecture")?.optJSONArray("output_modalities")
            modalities==null || (0 until modalities.length()).any { modalities.optString(it)=="text" }
        }
        else -> false
    }

    private fun readBounded(stream:java.io.InputStream?):String {
        if(stream==null) return ""
        return stream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            val output=StringBuilder(); val buffer=CharArray(4096)
            while(true) {
                val count=reader.read(buffer); if(count<0) break
                if(output.length+count>MAX_RESPONSE_CHARS) throw IllegalStateException("پاسخ فهرست مدل‌ها بیش از حد بزرگ است.")
                output.append(buffer,0,count)
            }
            output.toString()
        }
    }

    private companion object { const val MAX_RESPONSE_CHARS=2*1024*1024 }
}
