package ai.kira.mobile.application.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCallStateMachineTest {
    @Test
    fun followsConnectMuteUnmuteAndEndLifecycle() {
        val state = VoiceCallStateMachine()
        state.begin()
        assertEquals(VoiceCallState.Connecting, state.state)
        state.connected()
        state.setMuted(true)
        assertEquals(VoiceCallState.Muted, state.state)
        state.setMuted(false)
        assertEquals(VoiceCallState.Connected, state.state)
        state.end()
        assertEquals(VoiceCallState.Idle, state.state)
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsMuteBeforeConnection() {
        VoiceCallStateMachine().setMuted(true)
    }

    @Test
    fun failedSessionCanBeRetried() {
        val state = VoiceCallStateMachine()
        state.begin()
        state.fail("temporary failure")
        state.begin()
        assertEquals(VoiceCallState.Connecting, state.state)
    }
}
