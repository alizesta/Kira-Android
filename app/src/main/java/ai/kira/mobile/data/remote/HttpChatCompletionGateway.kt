package ai.kira.mobile.data.remote

import ai.kira.mobile.domain.model.ChatMessage
import ai.kira.mobile.domain.model.ProviderRoute
import ai.kira.mobile.domain.repository.ChatCompletionGateway
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Provider HTTP adapter: OpenAI-compatible chat completions and Gemini generateContent. */
class HttpChatCompletionGateway:ChatCompletionGateway {
    override fun complete(route:ProviderRoute,apiKey:String,systemPrompt:String,history:List<ChatMessage>):String {
        require(apiKey.isNotBlank()) { "API Key برای ${route.providerId} ذخیره نشده است." }
        return if(route.providerId=="gemini") completeGemini(route,apiKey,systemPrompt,history)
        else completeOpenAi(route,apiKey,systemPrompt,history)
    }

    private fun completeOpenAi(route:ProviderRoute,key:String,prompt:String,history:List<ChatMessage>):String {
        val messages=JSONArray().put(JSONObject().put("role","system").put("content",prompt))
        history.forEach { messages.put(JSONObject().put("role",if(it.role=="assistant") "assistant" else "user").put("content",it.content)) }
        val body=JSONObject().put("model",route.modelId).put("messages",messages).put("temperature",0.75)
        val response=request(route.baseUrl.trimEnd('/')+"/chat/completions",key,body,route.providerId)
        val choices=response.optJSONArray("choices") ?: throw IllegalStateException("پاسخ Provider فیلد choices ندارد.")
        val content=choices.optJSONObject(0)?.optJSONObject("message")?.opt("content")
        val answer=when(content) {
            is String -> content.trim()
            is JSONArray -> (0 until content.length()).mapNotNull { content.optJSONObject(it)?.optString("text") }.joinToString("\n").trim()
            else -> ""
        }
        if(answer.isBlank()) throw IllegalStateException("Provider پاسخ متنی برنگرداند.")
        return answer
    }

    private fun completeGemini(route:ProviderRoute,key:String,prompt:String,history:List<ChatMessage>):String {
        val contents=JSONArray()
        history.forEach { message ->
            contents.put(JSONObject().put("role",if(message.role=="assistant") "model" else "user")
                .put("parts",JSONArray().put(JSONObject().put("text",message.content))))
        }
        val system=JSONObject().put("parts",JSONArray().put(JSONObject().put("text",prompt)))
        val body=JSONObject().put("systemInstruction",system).put("contents",contents)
            .put("generationConfig",JSONObject().put("temperature",0.75))
        val endpoint=route.baseUrl.trimEnd('/')+"/models/"+
            URLEncoder.encode(route.modelId,StandardCharsets.UTF_8.name())+":generateContent"
        val response=request(endpoint,key,body,"gemini")
        val parts=response.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val answer=(0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.optString("text") }
            .filter { it.isNotBlank() }.joinToString("\n").trim()
        if(answer.isBlank()) throw IllegalStateException("Gemini پاسخ متنی برنگرداند.")
        return answer
    }

    private fun request(url:String,key:String,body:JSONObject,provider:String):JSONObject {
        val connection=(URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod="POST"; connectTimeout=15_000; readTimeout=90_000; doOutput=true
            setRequestProperty("Content-Type","application/json; charset=utf-8")
            setRequestProperty("Accept","application/json")
            if(provider=="gemini") setRequestProperty("x-goog-api-key",key)
            else setRequestProperty("Authorization","Bearer "+key)
            if(provider=="openrouter") {
                setRequestProperty("HTTP-Referer","https://github.com/alizesta/Kira")
                setRequestProperty("X-Title","Kira")
            }
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            val status=connection.responseCode
            val stream=if(status in 200..299) connection.inputStream else connection.errorStream
            val raw=stream?.bufferedReader(StandardCharsets.UTF_8)?.use { reader ->
                val out=StringBuilder(); val buffer=CharArray(4096)
                while(true) {
                    val count=reader.read(buffer); if(count<0) break
                    if(out.length+count>MAX_RESPONSE_CHARS) throw IllegalStateException("پاسخ Provider از حد مجاز بزرگ‌تر است.")
                    out.append(buffer,0,count)
                }
                out.toString()
            }.orEmpty()
            if(status !in 200..299) {
                val detail=runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
                throw IllegalStateException("HTTP "+status+" "+detail.take(250))
            }
            if(raw.isBlank()) throw IllegalStateException("Provider پاسخ خالی برگرداند.")
            return JSONObject(raw)
        } finally { connection.disconnect() }
    }

    private companion object { const val MAX_RESPONSE_CHARS=2*1024*1024 }
}
