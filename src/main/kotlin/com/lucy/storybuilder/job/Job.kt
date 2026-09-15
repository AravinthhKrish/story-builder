package com.lucy.storybuilder.job

import com.lucy.storybuilder.pipeline.JobSpec
import com.lucy.storybuilder.pipeline.breakdown.Scene
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class JobStatus {
    QUEUED,
    BREAKDOWN,
    ASSETS,
    LAYOUT,
    RENDERING,
    DONE,
    FAILED,
}

data class SceneResult(
    val scene: Scene,
    /** Final slot on the timeline; null until the audio for the scene exists. */
    val durationSeconds: Double? = null,
)

/** A render job. Written by one pipeline thread, read by HTTP threads, hence the volatile fields. */
class Job(
    val id: UUID,
    val spec: JobSpec,
) {
    val createdAt: Instant = Instant.now()

    @Volatile var status: JobStatus = JobStatus.QUEUED
        private set

    @Volatile var progress: Int = 0

    @Volatile var scenes: List<SceneResult> = emptyList()

    @Volatile var durationSeconds: Double? = null

    @Volatile var error: String? = null
        private set

    @Volatile var output: Path? = null
        private set

    @Volatile var finishedAt: Instant? = null
        private set

    fun advance(
        stage: JobStatus,
        progressAtStart: Int,
    ) {
        status = stage
        progress = progressAtStart
    }

    fun complete(video: Path) {
        output = video
        progress = 100
        finishedAt = Instant.now()
        status = JobStatus.DONE
    }

    fun fail(message: String) {
        error = message
        finishedAt = Instant.now()
        status = JobStatus.FAILED
    }
}

/** In-memory job registry. Jobs do not survive a restart; their output files stay in the work dir. */
@Component
class JobStore {
    private val jobs = ConcurrentHashMap<UUID, Job>()

    fun create(spec: JobSpec): Job = Job(UUID.randomUUID(), spec).also { jobs[it.id] = it }

    fun get(id: UUID): Job? = jobs[id]
}
