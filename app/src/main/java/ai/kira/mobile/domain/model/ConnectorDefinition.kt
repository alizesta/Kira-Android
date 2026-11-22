package ai.kira.mobile.domain.model

/** Read-only API connector enabled for a character. */
data class ConnectorDefinition(val id:String,val description:String,val parameters:List<String>)
