package ai.kira.mobile.domain.repository

import ai.kira.mobile.domain.model.MemoryNote
import ai.kira.mobile.domain.model.SkillEntry

/** Persistence port for character-scoped memories and the shared skill library. */
interface AssistantRepository {
    fun memories(characterId: String): List<MemoryNote>
    fun addMemory(characterId: String, text: String)
    fun deleteMemory(id: Long)
    fun skillsFor(characterId: String): List<SkillEntry>
    fun setSkillEnabled(name: String, characterId: String, enabled: Boolean)
    fun removeSkill(name: String)
    fun installApiSkill(
        name: String,
        description: String,
        instructions: String,
        baseUrl: String,
        pathTemplate: String,
        allowedParameters: List<String>,
        apiKey: String,
        characterId: String,
        source: String
    )
}
