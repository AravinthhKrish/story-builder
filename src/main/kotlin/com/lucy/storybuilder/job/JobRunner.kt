package com.lucy.storybuilder.job

import com.lucy.storybuilder.config.StoryBuilderProperties
import com.lucy.storybuilder.pipeline.JobSpec
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.Executors

/**
 * Queues jobs onto a fixed pool of platform threads. Rendering is CPU-bound (Java2D + libx264), so
 * concurrency is capped by `storybuilder.max-concurrent-jobs`; extra jobs wait as QUEUED.
 */
@Component
class JobRunner(
    props: StoryBuilderProperties,
    private val store: JobStore,
    private val pipeline: StoryPipeline,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val executor =
        Executors.newFixedThreadPool(
            props.maxConcurrentJobs,
            Thread.ofPlatform().name("render-", 1).factory(),
        )

    fun submit(spec: JobSpec): Job {
        val job = store.create(spec)
        executor.execute { execute(job) }
        return job
    }

    private fun execute(job: Job) {
        val started = System.nanoTime()
        try {
            pipeline.run(job)
            log.info("Job {} done: {} scenes, {}s video in {} ms", job.id, job.scenes.size, job.durationSeconds, elapsedMs(started))
        } catch (e: Exception) {
            log.error("Job {} failed during {}", job.id, job.status, e)
            job.fail(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun elapsedMs(since: Long) = (System.nanoTime() - since) / 1_000_000

    @PreDestroy
    fun shutdown() {
        executor.shutdownNow()
    }
}
