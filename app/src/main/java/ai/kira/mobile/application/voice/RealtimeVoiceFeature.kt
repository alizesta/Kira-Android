package ai.kira.mobile.application.voice

import ai.kira.mobile.domain.model.RealtimeVoiceSessionEvent
import ai.kira.mobile.domain.repository.RealtimeVoiceSession
import ai.kira.mobile.domain.repository.RealtimeVoiceSessionFactory
import ai.kira.mobile.domain.repository.VoiceCredentialRepository

sealed class VoiceCallState {
    data object Idle : VoiceCallState()
    data object Connecting : VoiceCallState()
    data object Connected : VoiceCallState()
    data object Muted : VoiceCallState()
    data class Failed(val reason: String) : VoiceCallState()
}

/** Pure lifecycle policy for a real-time voice call. */
class VoiceCallStateMachine {
    var state: VoiceCallState = VoiceCallState.Idle
        private set

    fun begin() {
        check(state is VoiceCallState.Idle || state is VoiceCallState.Failed) {
            "Voice session is already active."
        }
        state = VoiceCallState.Connecting
    }

    fun connected() {
        check(state is VoiceCallState.Connecting || state is VoiceCallState.Muted) {
            "Voice session is not connecting."
        }
        state = VoiceCallState.Connected
    }

    fun setMuted(muted: Boolean) {
        check(state is VoiceCallState.Connected || state is VoiceCallState.Muted) {
            "Voice session is not connected."
        }
        state = if (muted) VoiceCallState.Muted else VoiceCallState.Connected
    }

    fun fail(reason: String) {
        state = VoiceCallState.Failed(reason.ifBlank { "Voice session failed." })
    }

    fun end() {
        state = VoiceCallState.Idle
    }
}

/**
 * Coordinates credentials, session creation and call-state transitions.
 * The Android audio/WebSocket implementation is injected through a domain port.
 */
class RealtimeVoiceInteractor(
    private val credentials: VoiceCredentialRepository,
    private val sessionFactory: RealtimeVoiceSessionFactory
) {
    private val stateMachine = VoiceCallStateMachine()
    private var activeSession: RealtimeVoiceSession? = null

    val state: VoiceCallState
        @Synchronized get() = stateMachine.state

    @Synchronized
    fun hasApiKey(): Boolean = credentials.hasApiKey()

    @Synchronized
    fun saveApiKey(value: String) {
        check(!isActiveState(stateMachine.state)) { "ابتدا تماس صوتی را پایان بده." }
        val key = value.trim()
        require(key.isNotEmpty() && key.length <= MAX_API_KEY_LENGTH) { "کلید Gemini Live معتبر نیست." }
        require(!key.contains('\n') && !key.contains('\r')) { "کلید Gemini Live معتبر نیست." }
        credentials.saveApiKey(key)
    }

    @Synchronized
    fun clearApiKey() {
        check(!isActiveState(stateMachine.state)) { "هنگام تماس زنده نمی‌توان کلید را حذف کرد." }
        credentials.clearApiKey()
    }

    @Synchronized
    fun start(
        onStatus: (String) -> Unit,
        onTranscript: (speaker: String, text: String) -> Unit
    ) {
        check(!isActiveState(stateMachine.state)) { "تماس صوتی از قبل فعال است." }
        val key = credentials.readApiKey()
        stateMachine.begin()

        var createdSession: RealtimeVoiceSession? = null
        val session = try {
            sessionFactory.create(
                apiKey = key,
                onEvent = { event ->
                    synchronized(this) {
                        if (createdSession != null && activeSession === createdSession) {
                            updateStateFromSessionEvent(event)
                        }
                    }
                },
                onStatus = onStatus,
                onTranscript = onTranscript
            )
        } catch (error: Exception) {
            stateMachine.fail(error.message.orEmpty())
            throw error
        }

        createdSession = session
        activeSession = session
        try {
            session.start()
        } catch (error: Exception) {
            activeSession = null
            stateMachine.fail(error.message.orEmpty())
            runCatching { session.stop() }
            throw error
        }
    }

    @Synchronized
    fun setMuted(muted: Boolean) {
        check(stateMachine.state is VoiceCallState.Connected || stateMachine.state is VoiceCallState.Muted) {
            "تماس صوتی متصل نیست."
        }
        val session = activeSession ?: throw IllegalStateException("نشست صوتی در دسترس نیست.")
        session.setMuted(muted)
        stateMachine.setMuted(muted)
    }

    @Synchronized
    fun isMuted(): Boolean = stateMachine.state is VoiceCallState.Muted

    @Synchronized
    fun isActive(): Boolean = isActiveState(stateMachine.state)

    @Synchronized
    fun stop() {
        val current = activeSession
        activeSession = null
        stateMachine.end()
        current?.stop()
    }

    private fun updateStateFromSessionEvent(event: RealtimeVoiceSessionEvent) {
        when (event) {
            RealtimeVoiceSessionEvent.Connected -> {
                if (stateMachine.state is VoiceCallState.Connecting) stateMachine.connected()
            }
            RealtimeVoiceSessionEvent.Muted -> {
                if (stateMachine.state is VoiceCallState.Connected || stateMachine.state is VoiceCallState.Muted) {
                    stateMachine.setMuted(true)
                }
            }
            RealtimeVoiceSessionEvent.Unmuted -> {
                if (stateMachine.state is VoiceCallState.Connected || stateMachine.state is VoiceCallState.Muted) {
                    stateMachine.setMuted(false)
                }
            }
            RealtimeVoiceSessionEvent.Ended -> stateMachine.end()
            is RealtimeVoiceSessionEvent.Failed -> stateMachine.fail(event.reason)
        }
        if (stateMachine.state is VoiceCallState.Idle || stateMachine.state is VoiceCallState.Failed) {
            activeSession = null
        }
    }

    private fun isActiveState(state: VoiceCallState): Boolean =
        state is VoiceCallState.Connecting || state is VoiceCallState.Connected || state is VoiceCallState.Muted

    private companion object {
        const val MAX_API_KEY_LENGTH = 512
    }
}
