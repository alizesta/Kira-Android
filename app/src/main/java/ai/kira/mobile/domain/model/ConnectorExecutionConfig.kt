package ai.kira.mobile.domain.model

/** Validated local connector configuration; the decrypted credential is short-lived in memory only. */
data class ConnectorExecutionConfig(
    val id:String,
    val baseUrl:String,
    val pathTemplate:String,
    val allowedParameters:List<String>,
    val authType:String,
    val apiKey:String
)
