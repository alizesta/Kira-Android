package ai.kira.mobile.domain.model

/** Platform-neutral long-term memory entry. */
data class MemoryNote(val id: Long, val text: String)

/** Platform-neutral summary of an installed skill and its character assignment. */
data class SkillEntry(
    val name: String,
    val description: String,
    val instructions: String,
    val source: String,
    val enabled: Boolean
)
