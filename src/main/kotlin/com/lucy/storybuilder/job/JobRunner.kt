package com.lucy.storybuilder.job

import com.lucy.storybuilder.config.StoryBuilderProperties
import com.lucy.storybuilder.pipeline.JobSpec
import com.sun.management.OperatingSystemMXBean
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.lang.management.ManagementFactory
import java.util.concurrent.Executors

/**
 * Queues jobs onto a fixed pool of platform threads; extra jobs wait as QUEUED. A job is CPU-heavy
 * (parallel Java2D drawing + libx264) and memory-heavy (JVM frames plus Piper and ffmpeg child
 * processes), so `storybuilder.max-concurrent-jobs=0` sizes the pool to the machine: one job per
 * [MEMORY_PER_JOB_BYTES] of memory and per [CORES_PER_JOB] cores, at least one. Both limits are
 * container-aware. Running two jobs in a 2 GB Docker VM got the JVM OOM-killed.
 */
@Component
class JobRunner(
    props: StoryBuilderProperties,
    private val store: JobStore,
    private val pipeline: StoryPipeline,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val concurrency = props.maxConcurrentJobs.takeIf { it > 0 } ?: autoConcurrency()
    private val executor =
        Executors.newFixedThreadPool(
            concurrency,
            Thread.ofPlatform().name("render-", 1).factory(),
        )

    init {
        log.info("Running up to {} job(s) at once", concurrency)
    }

    private fun autoConcurrency(): Int {
        val memory = (ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean).totalMemorySize
        val byMemory = (memory / MEMORY_PER_JOB_BYTES).toInt()
        val byCores = Runtime.getRuntime().availableProcessors() / CORES_PER_JOB
        return minOf(byMemory, byCores).coerceAtLeast(1)
    }

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

    private companion object {
        const val MEMORY_PER_JOB_BYTES = 1_500_000_000L
        const val CORES_PER_JOB = 4
    }
}
