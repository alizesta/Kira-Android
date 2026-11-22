package ai.kira.mobile.application.assistant

import ai.kira.mobile.domain.model.MemoryNote
import ai.kira.mobile.domain.model.SkillEntry
import ai.kira.mobile.domain.repository.AssistantRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantInteractorTest {
    @Test
    fun addMemoryTrimsTextBeforePersisting() {
        val repository = FakeAssistantRepository()
        AssistantInteractor(repository).addMemory(" kira ", "  remember this  ")
        assertEquals("kira", repository.characterId)
        assertEquals("remember this", repository.memoryText)
    }

    @Test(expected = IllegalArgumentException::class)
    fun addMemoryRejectsBlankText() {
        AssistantInteractor(FakeAssistantRepository()).addMemory("kira", "  ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun deleteMemoryRejectsInvalidId() {
        AssistantInteractor(FakeAssistantRepository()).deleteMemory(0)
    }

    private class FakeAssistantRepository : AssistantRepository {
        var characterId = ""
        var memoryText = ""
        override fun memories(characterId: String) = emptyList<MemoryNote>()
        override fun addMemory(characterId: String, text: String) {
            this.characterId = characterId
            memoryText = text
        }
        override fun deleteMemory(id: Long) = Unit
        override fun skillsFor(characterId: String) = emptyList<SkillEntry>()
        override fun setSkillEnabled(name: String, characterId: String, enabled: Boolean) = Unit
        override fun removeSkill(name: String) = Unit
        override fun installApiSkill(name: String, description: String, instructions: String, baseUrl: String,
            pathTemplate: String, allowedParameters: List<String>, apiKey: String, characterId: String, source: String) = Unit
    }
}
