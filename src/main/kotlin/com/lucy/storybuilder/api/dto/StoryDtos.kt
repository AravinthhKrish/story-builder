package com.lucy.storybuilder.api.dto

import com.lucy.storybuilder.job.Job
import com.lucy.storybuilder.job.JobStatus
import com.lucy.storybuilder.pipeline.timeline.CameraMove
import com.lucy.storybuilder.skills.RendererType
import com.lucy.storybuilder.skills.Skill
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

private const val HEX_COLOR = "#[0-9a-fA-F]{6}"
private const val SKILL_NAME = "[a-z0-9][a-z0-9-]{0,40}"

data class CreateStoryRequest(
    @field:NotBlank
    val text: String? = null,
    /** Style skill (see GET /api/v1/skills); defaults to `storybuilder.default-skill`. */
    @field:Pattern(regexp = SKILL_NAME)
    val skill: String? = null,
    @field:Valid
    val options: StoryOptions? = null,
)

/** Every field is optional; anything omitted falls back to the skill, then `storybuilder.*` defaults. */
data class StoryOptions(
    @field:Min(16) @field:Max(3840)
    val width: Int? = null,
    @field:Min(16) @field:Max(3840)
    val height: Int? = null,
    @field:Min(1) @field:Max(60)
    val fps: Int? = null,
    val startY: Double? = null,
    val scrollSpeed: Double? = null,
    @field:Min(60) @field:Max(400)
    val wordsPerMinute: Int? = null,
    /** Engine-specific voice: `say` → "Samantha", Piper → "en_US-lessac-medium", espeak-ng → "en-gb". */
    @field:Pattern(regexp = "[\\p{L}\\p{N} ()._-]{1,64}")
    val voice: String? = null,
    @field:Pattern(regexp = HEX_COLOR)
    val backgroundColor: String? = null,
    @field:Pattern(regexp = HEX_COLOR)
    val textColor: String? = null,
    @field:Min(8) @field:Max(300)
    val fontSize: Int? = null,
)

data class JobCreatedResponse(
    val jobId: UUID,
    val status: JobStatus,
    val skill: String,
    val statusUrl: String,
    val videoUrl: String,
)

data class SceneView(
    val index: Int,
    val text: String,
    val wordCount: Int,
    val estimatedSeconds: Double,
    val durationSeconds: Double?,
)

data class JobResponse(
    val jobId: UUID,
    val status: JobStatus,
    val progress: Int,
    val skill: String,
    val scriptId: UUID?,
    val durationSeconds: Double?,
    val scenes: List<SceneView>,
    /** True when something was substituted to stay within limits; see [notes]. */
    val degraded: Boolean,
    val notes: List<String>,
    /** Wall-clock ms per stage (director, assets, render) and total. */
    val timingsMs: Map<String, Long>,
    /** Delivered within the delivery target (default 60 s); null while running. */
    val slaMet: Boolean?,
    val error: String?,
    val createdAt: Instant,
    val finishedAt: Instant?,
    val videoUrl: String?,
) {
    companion object {
        fun of(
            job: Job,
            videoUrl: String,
        ) = JobResponse(
            jobId = job.id,
            status = job.status,
            progress = job.progress,
            skill = job.spec.skill.name,
            scriptId = job.script?.id,
            durationSeconds = job.durationSeconds,
            scenes =
                job.scenes.map {
                    SceneView(it.scene.index, it.scene.text, it.scene.wordCount, it.scene.estimatedSeconds, it.durationSeconds)
                },
            degraded = job.degraded,
            notes = job.notes.toList(),
            timingsMs = synchronized(job.timingsMs) { LinkedHashMap(job.timingsMs) },
            slaMet = job.slaMet,
            error = job.error,
            createdAt = job.createdAt,
            finishedAt = job.finishedAt,
            videoUrl = videoUrl.takeIf { job.status == JobStatus.DONE },
        )
    }
}

// ---- staged API: scripts --------------------------------------------------------------------

data class CreateScriptRequest(
    @field:NotBlank
    val text: String? = null,
    @field:Pattern(regexp = SKILL_NAME)
    val skill: String? = null,
)

/** Partial update: omitted fields stay as they are; `scenes` and `characters` replace the whole list. */
data class UpdateScriptRequest(
    @field:Size(max = 200)
    val title: String? = null,
    val seed: Long? = null,
    @field:Valid @field:Size(max = 20)
    val characters: List<CharacterInput>? = null,
    @field:Valid @field:Size(min = 1, max = 12)
    val scenes: List<SceneInput>? = null,
)

data class CharacterInput(
    @field:NotBlank @field:Pattern(regexp = "[a-z0-9_-]{1,40}")
    val id: String? = null,
    @field:NotBlank @field:Size(max = 80)
    val name: String? = null,
    @field:NotBlank @field:Size(max = 400)
    val look: String? = null,
)

data class SceneInput(
    @field:NotBlank @field:Size(max = 1000)
    val narration: String? = null,
    @field:Size(max = 300) val setting: String? = null,
    @field:Size(max = 300) val action: String? = null,
    @field:Size(max = 60) val emotion: String? = null,
    val camera: CameraMove? = null,
    val characters: List<String>? = null,
    /** Leave blank to rebuild it from the skill's image template. */
    @field:Size(max = 2000)
    val imagePrompt: String? = null,
)

data class RenderScriptRequest(
    @field:Valid
    val options: StoryOptions? = null,
)

data class SkillView(
    val name: String,
    val description: String,
    val renderer: RendererType,
    val width: Int,
    val height: Int,
    val fps: Int,
    val isDefault: Boolean,
) {
    companion object {
        fun of(
            skill: Skill,
            default: String,
        ) = SkillView(
            skill.name,
            skill.description,
            skill.renderer,
            skill.format.width,
            skill.format.height,
            skill.format.fps,
            skill.name == default,
        )
    }
}
