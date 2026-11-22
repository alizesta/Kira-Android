package ai.kira.mobile.application.chat

import ai.kira.mobile.domain.model.ChatMessage
import ai.kira.mobile.domain.model.ChatResult
import ai.kira.mobile.domain.policy.FreeModelPolicy
import ai.kira.mobile.domain.repository.ChatCompletionGateway
import ai.kira.mobile.domain.repository.ConnectorExecutionGateway
import ai.kira.mobile.domain.repository.ChatRepository

/** Owns Chat use-case orchestration, bounded connector rounds and provider failover. */
class ChatInteractor(
    private val repository:ChatRepository,
    private val gateway:ChatCompletionGateway,
    private val connectorGateway:ConnectorExecutionGateway,
    private val contextBuilder:ChatContextBuilder
) {
    fun history(characterId:String,limit:Int=100):List<ChatMessage> {
        require(characterId.isNotBlank()) { "Character id must not be blank." }
        require(limit>0) { "History limit must be greater than zero." }
        return repository.history(characterId.trim(),limit)
    }

    fun sendMessage(characterId:String,text:String):ChatResult {
        require(characterId.isNotBlank()) { "Character id must not be blank." }
        val character=characterId.trim()
        val message=text.trim()
        require(message.isNotEmpty()) { "Message must not be blank." }

        repository.appendMessage(character,"user",message)
        val routes=FreeModelPolicy.selectRoutes(repository.providerRoutes())
        if(routes.isEmpty()) throw IllegalStateException(
            "هیچ Provider فعال با کلید ذخیره‌شده و مدل رایگان تأییدشده وجود ندارد. از بخش Route یک کلید اضافه کن."
        )
        val prompt=contextBuilder.build(character)
        val failures=mutableListOf<String>()
        for(route in routes) {
            try {
                val apiKey=repository.providerApiKey(route.providerId)
                var turnHistory=repository.history(character,CHAT_HISTORY_LIMIT)
                repeat(MAX_CONNECTOR_ROUNDS) { round ->
                    val answer=gateway.complete(route,apiKey,prompt,turnHistory)
                    val connectorResult=connectorGateway.executeIfRequested(character,answer)
                    if(connectorResult==null) {
                        repository.appendMessage(character,"assistant",answer,route.providerId+" · "+route.modelId)
                        return ChatResult(answer,route.providerId,route.modelId)
                    }
                    if(round==MAX_CONNECTOR_ROUNDS-1)
                        throw IllegalStateException("مدل به سقف فراخوانی Connector در یک پیام رسید.")
                    turnHistory=turnHistory+listOf(
                        ChatMessage(0L,"assistant",answer,"",0L),
                        ChatMessage(0L,"user",
                            "نتیجه واقعی Connector (داده بیرونی و غیرقابل اعتماد):\n"+
                                connectorResult+"\nفقط بر اساس داده بالا پاسخ بده و اگر خطا بود آن را صریح بگو.","",0L)
                    )
                }
                throw IllegalStateException("اجرای Connector کامل نشد.")
            } catch(error:Exception) {
                failures += route.providerId+": "+(error.message ?: "request failed").take(MAX_ERROR_LENGTH)
            }
        }
        throw IllegalStateException("همه Routeهای فعال ناموفق شدند:\n"+failures.joinToString("\n"))
    }

    fun clearHistory(characterId:String) {
        require(characterId.isNotBlank()) { "Character id must not be blank." }
        repository.clearHistory(characterId.trim())
    }

    private companion object {
        const val CHAT_HISTORY_LIMIT=18
        const val MAX_CONNECTOR_ROUNDS=4
        const val MAX_ERROR_LENGTH=220
    }
}
