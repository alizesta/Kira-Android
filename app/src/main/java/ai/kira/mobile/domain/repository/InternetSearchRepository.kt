package ai.kira.mobile.domain.repository

import ai.kira.mobile.domain.model.InternetSearchResponse

interface InternetSearchRepository {
    fun search(query: String, maxResults: Int = 6): InternetSearchResponse
}

interface SearchCredentialRepository {
    fun hasApiKey(): Boolean
    fun saveApiKey(value: String)
    fun clearApiKey()
}
