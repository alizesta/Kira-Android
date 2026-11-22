package ai.kira.mobile.domain.repository

import ai.kira.mobile.domain.model.RealtimeVoiceSessionEvent

/** Device-local credential storage for a real-time voice provider. */
interface VoiceCredentialRepository {
    fun hasApiKey(): Boolean
    fun saveApiKey(value: String)
    fun readApiKey(): String
    fun clearApiKey()
}

interface RealtimeVoiceSession {
    fun start()
    fun setMuted(value: Boolean)
    fun isMuted(): Boolean
    fun stop()
}

interface RealtimeVoiceSessionFactory {
    fun create(
        apiKey: String,
        onEvent: (RealtimeVoiceSessionEvent) -> Unit,
        onStatus: (String) -> Unit,
        onTranscript: (speaker: String, text: String) -> Unit
    ): RealtimeVoiceSession
}
