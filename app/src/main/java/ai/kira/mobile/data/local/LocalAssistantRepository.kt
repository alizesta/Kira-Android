package ai.kira.mobile.data.local

import ai.kira.mobile.domain.model.MemoryNote
import ai.kira.mobile.domain.model.SkillEntry
import ai.kira.mobile.domain.repository.AssistantRepository

/** Android/SQLite adapter for the assistant library repository. */
class LocalAssistantRepository(private val core: SqliteKiraStore) : AssistantRepository {
    override fun memories(characterId: String): List<MemoryNote> =
        core.memories(characterId).map { MemoryNote(it.first, it.second) }

    override fun addMemory(characterId: String, text: String) =
        core.addMemory(characterId, text)

    override fun deleteMemory(id: Long) = core.deleteMemory(id)

    override fun skillsFor(characterId: String): List<SkillEntry> =
        core.skillsFor(characterId).map {
            SkillEntry(it.name, it.description, it.instructions, it.source, it.enabled)
        }

    override fun setSkillEnabled(name: String, characterId: String, enabled: Boolean) =
        core.setSkillEnabled(name, characterId, enabled)

    override fun removeSkill(name: String) = core.removeSkill(name)

    override fun installApiSkill(
        name: String,
        description: String,
        instructions: String,
        baseUrl: String,
        pathTemplate: String,
        allowedParameters: List<String>,
        apiKey: String,
        characterId: String,
        source: String
    ) = core.installApiSkill(
        name, description, instructions, baseUrl, pathTemplate,
        allowedParameters, apiKey, characterId, source
    )
}
