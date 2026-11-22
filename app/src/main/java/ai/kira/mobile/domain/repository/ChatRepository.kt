package ai.kira.mobile.domain.repository

import ai.kira.mobile.domain.model.ChatMessage
import ai.kira.mobile.domain.model.ChatAttachmentInput
import ai.kira.mobile.domain.model.ConnectorDefinition
import ai.kira.mobile.domain.model.ProviderRoute

/** Ports for local Chat persistence, provider credentials and capability execution. */
interface ChatRepository {
    fun history(characterId:String,limit:Int=100):List<ChatMessage>
    fun providerRoutes():List<ProviderRoute>
    fun providerApiKey(providerId:String):String
    fun appendMessage(characterId:String,role:String,content:String,route:String="",attachments:List<ChatAttachmentInput> = emptyList())
    fun connectorsFor(characterId:String):List<ConnectorDefinition>
    fun clearHistory(characterId:String)
}
