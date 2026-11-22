package ai.kira.mobile.application.assistant

import ai.kira.mobile.domain.model.MemoryNote
import ai.kira.mobile.domain.model.SkillEntry
import ai.kira.mobile.domain.repository.AssistantRepository

/** Validates user intent and delegates local assistant-library operations to a repository port. */
class AssistantInteractor(private val repository: AssistantRepository) {
    fun memories(characterId: String): List<MemoryNote> {
        require(characterId.isNotBlank()) { "Character id must not be blank." }
        return repository.memories(characterId.trim())
    }

    fun addMemory(characterId: String, text: String) {
        require(characterId.isNotBlank()) { "Character id must not be blank." }
        val normalized = text.trim()
        require(normalized.isNotEmpty()) { "Memory text must not be blank." }
        repository.addMemory(characterId.trim(), normalized)
    }

    fun deleteMemory(id: Long) {
        require(id > 0) { "Memory id must be positive." }
        repository.deleteMemory(id)
    }

    fun skillsFor(characterId: String): List<SkillEntry> {
        require(characterId.isNotBlank()) { "Character id must not be blank." }
        return repository.skillsFor(characterId.trim())
    }

    fun setSkillEnabled(name: String, characterId: String, enabled: Boolean) {
        require(name.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) { "Skill id is invalid." }
        require(characterId in setOf("global", "kira", "ren", "mika")) { "Character id is invalid." }
        repository.setSkillEnabled(name, characterId, enabled)
    }

    fun removeSkill(name: String) {
        require(name.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) { "Skill id is invalid." }
        repository.removeSkill(name)
    }

    fun installApiSkill(
        name: String,
        description: String,
        instructions: String,
        baseUrl: String,
        pathTemplate: String,
        allowedParameters: List<String>,
        apiKey: String,
        characterId: String,
        source: String = "custom-api"
    ) {
        require(name.isNotBlank()) { "Skill name must not be blank." }
        require(description.isNotBlank()) { "Skill description must not be blank." }
        require(baseUrl.startsWith("https://")) { "Skill endpoints must use HTTPS." }
        require(allowedParameters.isNotEmpty()) { "At least one allowed parameter is required." }
        repository.installApiSkill(
            name.trim(), description.trim(), instructions.trim(), baseUrl.trim(), pathTemplate.trim(),
            allowedParameters.map(String::trim).filter(String::isNotEmpty).distinct(),
            apiKey.trim(), characterId, source
        )
    }
}
