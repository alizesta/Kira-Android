package ai.kira.mobile.data.local

import ai.kira.mobile.domain.model.ChatAttachmentInfo
import ai.kira.mobile.domain.model.ChatAttachmentInput
import ai.kira.mobile.domain.model.ChatMessage
import ai.kira.mobile.domain.model.ConnectorDefinition
import ai.kira.mobile.domain.model.ConnectorExecutionConfig
import ai.kira.mobile.domain.model.ProviderRoute
import ai.kira.mobile.domain.repository.ChatRepository
import ai.kira.mobile.domain.repository.ConnectorConfigurationRepository


/** SQLite/Android Keystore adapter for the Chat repository port. */
class LocalChatRepository(private val core:SqliteKiraStore):ChatRepository, ConnectorConfigurationRepository {
    override fun history(characterId:String,limit:Int):List<ChatMessage> =
        core.history(characterId,limit).map { m -> ChatMessage(m.id,m.role,m.content,m.route,m.createdAt,m.attachments.map { a -> ChatAttachmentInfo(a.id,a.name,a.mimeType,a.sizeBytes) }) }

    override fun providerRoutes():List<ProviderRoute> = core.providers().map {
        ProviderRoute(it.id,it.model,it.baseUrl,it.enabled,it.priority,it.hasKey)
    }

    override fun providerApiKey(providerId:String):String = core.providerApiKey(providerId)

    override fun appendMessage(characterId:String,role:String,content:String,route:String,attachments:List<ChatAttachmentInput>) =
        core.appendChatMessage(characterId,role,content,route,attachments)

    override fun connectorsFor(characterId:String):List<ConnectorDefinition> =
        core.connectorDefinitions(characterId).map { ConnectorDefinition(it.id,it.description,it.parameters) }

    override fun executionConfig(characterId:String,connectorId:String):ConnectorExecutionConfig? =
        core.connectorExecutionConfig(characterId,connectorId)?.let {
            ConnectorExecutionConfig(it.id,it.baseUrl,it.pathTemplate,it.allowedParameters,it.authType,it.apiKey)
        }

    override fun clearHistory(characterId:String) = core.clearHistory(characterId)
}
