package com.lucy.storybuilder.api.dto

import com.lucy.storybuilder.job.Job
import com.lucy.storybuilder.job.JobStatus
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import java.time.Instant
import java.util.UUID

private const val HEX_COLOR = "#[0-9a-fA-F]{6}"

data class CreateStoryRequest(
    @field:NotBlank
    val text: String? = null,
    @field:Valid
    val options: StoryOptions? = null,
)

/** Every field is optional; anything omitted falls back to `storybuilder.render.*` / `storybuilder.breakdown.*`. */
data class StoryOptions(
    @field:Min(16) @field:Max(3840)
    val width: Int? = null,
    @field:Min(16) @field:Max(2160)
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
    val durationSeconds: Double?,
    val scenes: List<SceneView>,
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
            durationSeconds = job.durationSeconds,
            scenes =
                job.scenes.map {
                    SceneView(it.scene.index, it.scene.text, it.scene.wordCount, it.scene.estimatedSeconds, it.durationSeconds)
                },
            error = job.error,
            createdAt = job.createdAt,
            finishedAt = job.finishedAt,
            videoUrl = videoUrl.takeIf { job.status == JobStatus.DONE },
        )
    }
}
