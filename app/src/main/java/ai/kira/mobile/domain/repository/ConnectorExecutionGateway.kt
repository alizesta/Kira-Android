package ai.kira.mobile.domain.repository

/** Executes a model-requested connector call, or returns null when no call was requested. */
interface ConnectorExecutionGateway {
    fun executeIfRequested(characterId:String,assistantContent:String):String?
}
