package ai.kira.mobile.application.voice

import ai.kira.mobile.domain.model.RealtimeVoiceSessionEvent
import ai.kira.mobile.domain.repository.RealtimeVoiceSession
import ai.kira.mobile.domain.repository.RealtimeVoiceSessionFactory
import ai.kira.mobile.domain.repository.VoiceCredentialRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeVoiceInteractorTest {
    @Test
    fun managesCredentialAndFullDuplexSessionLifecycle() {
        val credentials = FakeCredentials()
        val factory = FakeSessionFactory()
        val service = RealtimeVoiceInteractor(credentials, factory)
        val events = mutableListOf<String>()

        service.saveApiKey(" live-key ")
        assertTrue(service.hasApiKey())
        service.start(onStatus = { events.add(it) }, onTranscript = { speaker, text -> events.add(speaker + ":" + text) })

        assertEquals(VoiceCallState.Connected, service.state)
        assertEquals("live-key", factory.session.apiKey)
        factory.session.emitEvent(RealtimeVoiceSessionEvent.Muted)
        assertEquals(VoiceCallState.Muted, service.state)

        service.setMuted(false)
        assertEquals(VoiceCallState.Connected, service.state)
        factory.session.emitTranscript("Kira", "سلام")
        assertTrue(events.contains("Kira:سلام"))

        service.stop()
        assertEquals(VoiceCallState.Idle, service.state)
        assertTrue(factory.session.stopped)
    }

    @Test
    fun microphoneFailureNeverProducesConnectedState() {
        val factory = FakeSessionFactory(connectOnStart = false)
        val service = RealtimeVoiceInteractor(FakeCredentials(), factory)

        service.start(onStatus = {}, onTranscript = { _, _ -> })
        assertEquals(VoiceCallState.Connecting, service.state)

        factory.session.emitEvent(RealtimeVoiceSessionEvent.Failed("microphone initialization failed"))
        assertEquals(VoiceCallState.Failed("microphone initialization failed"), service.state)
        assertFalse(service.isActive())
    }

    @Test
    fun eventsFromPreviousSessionCannotChangeCurrentCallState() {
        val factory = FakeSessionFactory()
        val service = RealtimeVoiceInteractor(FakeCredentials(), factory)
        service.start(onStatus = {}, onTranscript = { _, _ -> })
        val first = factory.session

        first.emitEvent(RealtimeVoiceSessionEvent.Failed("network"))
        assertTrue(service.state is VoiceCallState.Failed)

        service.start(onStatus = {}, onTranscript = { _, _ -> })
        assertEquals(2, factory.sessions.size)
        assertEquals(VoiceCallState.Connected, service.state)

        first.emitEvent(RealtimeVoiceSessionEvent.Ended)
        assertEquals(VoiceCallState.Connected, service.state)

        service.stop()
        assertEquals(VoiceCallState.Idle, service.state)
    }

    @Test(expected = IllegalStateException::class)
    fun cannotDeleteCredentialDuringActiveCall() {
        val service = RealtimeVoiceInteractor(FakeCredentials(), FakeSessionFactory())
        service.start(onStatus = {}, onTranscript = { _, _ -> })
        service.clearApiKey()
    }

    private class FakeCredentials : VoiceCredentialRepository {
        private var value = "live-key"
        override fun hasApiKey(): Boolean = value.isNotBlank()
        override fun saveApiKey(value: String) { this.value = value }
        override fun readApiKey(): String = value
        override fun clearApiKey() { value = "" }
    }

    private class FakeSessionFactory(private val connectOnStart: Boolean = true) : RealtimeVoiceSessionFactory {
        val sessions = mutableListOf<FakeSession>()
        val session: FakeSession get() = sessions.last()
        override fun create(
            apiKey: String,
            onEvent: (RealtimeVoiceSessionEvent) -> Unit,
            onStatus: (String) -> Unit,
            onTranscript: (speaker: String, text: String) -> Unit
        ): RealtimeVoiceSession {
            return FakeSession(apiKey, onEvent, onStatus, onTranscript, connectOnStart)
                .also { sessions.add(it) }
        }
    }

    private class FakeSession(
        val apiKey: String,
        private val onEvent: (RealtimeVoiceSessionEvent) -> Unit,
        private val onStatus: (String) -> Unit,
        private val onTranscript: (String, String) -> Unit,
        private val connectOnStart: Boolean
    ) : RealtimeVoiceSession {
        var stopped = false
        private var muted = false

        override fun start() {
            if (connectOnStart) {
                onEvent(RealtimeVoiceSessionEvent.Connected)
                onStatus("تماس زنده برقرار شد؛ صحبت کن.")
            }
        }

        override fun setMuted(value: Boolean) {
            muted = value
            onEvent(if (value) RealtimeVoiceSessionEvent.Muted else RealtimeVoiceSessionEvent.Unmuted)
            onStatus(if (value) "میکروفون بی‌صداست." else "تماس زنده است؛ می‌توانی صحبت کنی.")
        }

        override fun isMuted(): Boolean = muted

        override fun stop() {
            stopped = true
            onEvent(RealtimeVoiceSessionEvent.Ended)
            onStatus("تماس صوتی پایان یافت.")
        }

        fun emitEvent(event: RealtimeVoiceSessionEvent) = onEvent(event)
        fun emitTranscript(speaker: String, text: String) = onTranscript(speaker, text)
    }
}
