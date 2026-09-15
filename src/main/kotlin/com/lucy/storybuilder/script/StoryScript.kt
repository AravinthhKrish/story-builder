package com.lucy.storybuilder.script

import com.lucy.storybuilder.pipeline.timeline.CameraMove
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The director's output and the unit of the staged API: reviewable and editable before rendering.
 * One [seed] per script keeps the illustration style and characters consistent across scenes.
 */
data class StoryScript(
    val id: UUID,
    val skill: String,
    val title: String,
    val seed: Long,
    val characters: List<ScriptCharacter>,
    val scenes: List<ScriptScene>,
    /** True when the rule-based fallback wrote the script because the LLM failed or timed out. */
    val degraded: Boolean = false,
    val notes: List<String> = emptyList(),
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = createdAt,
)

data class ScriptCharacter(
    val id: String,
    val name: String,
    /** Fixed visual description reused in every image prompt. */
    val look: String,
)

data class ScriptScene(
    /** Spoken by the narrator and shown as captions. */
    val narration: String,
    val setting: String = "",
    val action: String = "",
    val emotion: String = "",
    val camera: CameraMove = CameraMove.ZOOM_IN,
    /** Ids of the [ScriptCharacter]s visible in this scene. */
    val characters: List<String> = emptyList(),
    /** Filled from the skill's image.md unless supplied explicitly. */
    val imagePrompt: String = "",
)

@Component
class ScriptStore {
    private val scripts = ConcurrentHashMap<UUID, StoryScript>()

    fun save(script: StoryScript): StoryScript = script.also { scripts[it.id] = it }

    fun get(id: UUID): StoryScript? = scripts[id]
}
