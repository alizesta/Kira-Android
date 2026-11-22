package ai.kira.mobile.domain.model

/** Typed lifecycle events emitted by a real-time voice transport adapter. */
sealed interface RealtimeVoiceSessionEvent {
    data object Connected : RealtimeVoiceSessionEvent
    data object Muted : RealtimeVoiceSessionEvent
    data object Unmuted : RealtimeVoiceSessionEvent
    data object Ended : RealtimeVoiceSessionEvent
    data class Failed(val reason: String) : RealtimeVoiceSessionEvent
}
