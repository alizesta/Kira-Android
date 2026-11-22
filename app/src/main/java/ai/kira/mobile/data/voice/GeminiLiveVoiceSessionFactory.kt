package ai.kira.mobile.data.voice

import android.content.Context
import ai.kira.mobile.domain.model.RealtimeVoiceSessionEvent
import ai.kira.mobile.domain.repository.RealtimeVoiceSession
import ai.kira.mobile.domain.repository.RealtimeVoiceSessionFactory

/** Creates one disposable Android audio/WebSocket session for each call. */
class GeminiLiveVoiceSessionFactory(context: Context) : RealtimeVoiceSessionFactory {
    private val app = context.applicationContext

    override fun create(
        apiKey: String,
        onEvent: (RealtimeVoiceSessionEvent) -> Unit,
        onStatus: (String) -> Unit,
        onTranscript: (speaker: String, text: String) -> Unit
    ): RealtimeVoiceSession = GeminiLiveVoiceSession(app, apiKey, onEvent, onStatus, onTranscript)
}
