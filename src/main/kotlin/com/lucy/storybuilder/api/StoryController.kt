package com.lucy.storybuilder.api

import com.lucy.storybuilder.api.dto.CreateStoryRequest
import com.lucy.storybuilder.api.dto.JobCreatedResponse
import com.lucy.storybuilder.api.dto.JobResponse
import com.lucy.storybuilder.job.Job
import com.lucy.storybuilder.job.JobRunner
import com.lucy.storybuilder.job.JobStatus
import com.lucy.storybuilder.job.JobStore
import jakarta.validation.Valid
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class StoryController(
    private val specFactory: JobSpecFactory,
    private val runner: JobRunner,
    private val store: JobStore,
) {
    /** One-shot: story in, video out. Director + render run as one background job; poll the status URL. */
    @PostMapping("/stories")
    fun createStory(
        @Valid @RequestBody request: CreateStoryRequest,
    ): ResponseEntity<JobCreatedResponse> = accepted(runner.submit(specFactory.create(request)))

    @GetMapping("/jobs/{id}")
    fun getJob(
        @PathVariable id: UUID,
    ): JobResponse {
        val job = store.get(id) ?: throw JobNotFoundException(id)
        return JobResponse.of(job, "${jobUrl(id)}/video")
    }

    /** Streams the finished .mp4 (Range requests are supported, so browsers can seek). */
    @GetMapping("/jobs/{id}/video")
    fun getVideo(
        @PathVariable id: UUID,
    ): ResponseEntity<Resource> {
        val job = store.get(id) ?: throw JobNotFoundException(id)
        val output = job.output
        if (job.status != JobStatus.DONE || output == null) {
            val detail =
                if (job.status == JobStatus.FAILED) "Job failed: ${job.error}" else "Job is ${job.status} (${job.progress}%)"
            throw VideoNotReadyException(detail)
        }
        return ResponseEntity
            .ok()
            .contentType(MediaType.parseMediaType("video/mp4"))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition
                    .inline()
                    .filename("story-$id.mp4")
                    .build()
                    .toString(),
            ).body(FileSystemResource(output))
    }
}

/** `202 Accepted` + Location for a newly queued job; shared by the one-shot and staged endpoints. */
internal fun accepted(job: Job): ResponseEntity<JobCreatedResponse> {
    val statusUrl = jobUrl(job.id)
    return ResponseEntity
        .accepted()
        .location(URI.create(statusUrl))
        .body(JobCreatedResponse(job.id, job.status, job.spec.skill.name, statusUrl, "$statusUrl/video"))
}

internal fun jobUrl(id: UUID): String = apiUrl("/api/v1/jobs/{id}", id)

internal fun apiUrl(
    path: String,
    id: UUID,
): String =
    ServletUriComponentsBuilder
        .fromCurrentContextPath()
        .path(path)
        .buildAndExpand(id)
        .toUriString()
