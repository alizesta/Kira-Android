package ai.kira.mobile.domain.repository

import ai.kira.mobile.domain.model.ChatMessage
import ai.kira.mobile.domain.model.ProviderRoute

/** Provider-neutral single-turn completion; Application owns connector orchestration. */
interface ChatCompletionGateway {
    fun complete(route:ProviderRoute,apiKey:String,systemPrompt:String,history:List<ChatMessage>):String
}
