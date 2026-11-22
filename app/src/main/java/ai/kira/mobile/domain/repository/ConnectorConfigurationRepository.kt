package ai.kira.mobile.domain.repository

import ai.kira.mobile.domain.model.ConnectorExecutionConfig

/** Port for resolving a character-enabled connector configuration from local storage. */
interface ConnectorConfigurationRepository {
    fun executionConfig(characterId:String,connectorId:String):ConnectorExecutionConfig?
}
