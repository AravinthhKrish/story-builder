package com.lucy.storybuilder.job

import com.lucy.storybuilder.config.StoryBuilderProperties
import com.lucy.storybuilder.pipeline.assets.AudioAssembler
import com.lucy.storybuilder.pipeline.assets.SceneImageService
import com.lucy.storybuilder.pipeline.assets.SceneImages
import com.lucy.storybuilder.pipeline.assets.TextLayout
import com.lucy.storybuilder.pipeline.assets.tts.SpeechRequest
import com.lucy.storybuilder.pipeline.assets.tts.TtsEngine
import com.lucy.storybuilder.pipeline.breakdown.Scene
import com.lucy.storybuilder.pipeline.render.FfmpegEncoder
import com.lucy.storybuilder.pipeline.render.IllustratedRenderer
import com.lucy.storybuilder.pipeline.render.IllustratedScene
import com.lucy.storybuilder.pipeline.render.ParallelFrameSource
import com.lucy.storybuilder.pipeline.render.SceneRenderer
import com.lucy.storybuilder.pipeline.render.TextScrollRenderer
import com.lucy.storybuilder.pipeline.timeline.DurationBudget
import com.lucy.storybuilder.pipeline.timeline.LinearScroll
import com.lucy.storybuilder.pipeline.timeline.SceneCaptions
import com.lucy.storybuilder.pipeline.timeline.Timeline
import com.lucy.storybuilder.pipeline.timeline.TimelineBuilder
import com.lucy.storybuilder.process.Ffmpeg
import com.lucy.storybuilder.script.ScriptDirector
import com.lucy.storybuilder.script.ScriptStore
import com.lucy.storybuilder.script.StoryScript
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText

/**
 * Runs the four stages from docs/PIPELINE.md for one job — now with the director and illustrated
 * assets from docs/AI-PIPELINE.md — reporting stage, progress, per-stage timings and SLA as it goes.
 */
@Service
class StoryPipeline(
    private val props: StoryBuilderProperties,
    private val director: ScriptDirector,
    private val scripts: ScriptStore,
    private val tts: TtsEngine,
    private val images: SceneImageService,
    private val ffmpeg: Ffmpeg,
    private val textLayout: TextLayout,
    private val timelineBuilder: TimelineBuilder,
    private val audioAssembler: AudioAssembler,
    private val encoder: FfmpegEncoder,
    private val stats: PipelineStats,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val renderThreads =
        props.renderThreads.takeIf { it > 0 } ?: (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
    private val json = JsonMapper.builder().build()

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun run(job: Job) {
        val spec = job.spec
        val skill = spec.skill
        val dir = props.workDir.resolve(job.id.toString()).createDirectories()

        // 1. Story breakdown: the director (LLM, or rule-based fallback) writes the scene script
        job.advance(JobStatus.BREAKDOWN, 0)
        val script =
            timed(job, "director") {
                scripts.save(spec.script ?: director.direct(requireNotNull(spec.text), skill, directorBudget(job)))
            }
        attach(job, script)
        dir.resolve("script.json").writeText(json.writerWithDefaultPrettyPrinter().writeValueAsString(script))
        val scenes =
            script.scenes.mapIndexed { i, s ->
                val words = s.narration.split(Regex("\\s+")).count { it.isNotBlank() }
                Scene(i, s.narration, words, maxOf(props.breakdown.minSceneSeconds, words * 60.0 / spec.wordsPerMinute))
            }
        job.scenes = scenes.map { SceneResult(it) }

        // 2. Asset generation: narration and pictures for every scene, concurrently
        job.advance(JobStatus.ASSETS, 10)
        val clipsDir = dir.resolve("scenes").createDirectories()
        val (clips, pictures) = timed(job, "assets") { generateAssets(job, script, scenes, clipsDir) }
        pictures?.notes?.takeIf { it.isNotEmpty() }?.let { degrade(job, it) }
        val speech = clips.map { ffmpeg.probeDurationSeconds(it) }

        // 3. Timeline: fit the Shorts length limit, then lay scenes out on the frame grid
        job.advance(JobStatus.LAYOUT, 40)
        val fitted =
            DurationBudget.fit(
                audioSeconds = speech,
                // text-scroll keeps reading-speed slots; illustrated scenes follow the narration.
                minSlotSeconds = scenes.map { if (skill.isIllustrated) props.breakdown.minSceneSeconds else it.estimatedSeconds },
                paddingSeconds = props.breakdown.audioPaddingSeconds,
                maxTotalSeconds = props.sla.maxVideoSeconds,
                maxSpeedup = props.sla.maxSpeedup,
            )
        if (fitted.notes.isNotEmpty()) degrade(job, fitted.notes)
        val kept = fitted.sceneCount
        val timeline = timelineBuilder.build(fitted.slots, spec.format.fps)
        job.scenes = timeline.entries.map { SceneResult(scenes[it.index], timeline.seconds(it)) }
        job.durationSeconds = timeline.totalSeconds

        // 4. Video rendering: bind audio to the timeline, then stream frames into ffmpeg
        job.advance(JobStatus.RENDERING, 45)
        val output = dir.resolve("output.mp4")
        val narration = dir.resolve("narration.wav")
        timed(job, "render") {
            audioAssembler.assemble(clips.take(kept), timeline.entries.map(timeline::seconds), narration, fitted.speed)
            val newRenderer = rendererFactory(job, script, timeline, pictures, speech.map { it / fitted.speed })
            ParallelFrameSource(timeline.totalFrames, renderThreads, newRenderer).use { frames ->
                encoder.encode(
                    format = spec.format,
                    totalFrames = timeline.totalFrames,
                    narration = narration,
                    output = output,
                    frameAt = frames::frame,
                    onFrame = { job.progress = 45 + 54 * (it + 1) / timeline.totalFrames },
                )
            }
        }
        if (skill.isIllustrated) {
            stats.recordAssets(job.timingsMs["assets"] ?: 0)
            stats.recordRender(job.timingsMs["render"] ?: 0, timeline.totalFrames)
        }

        clipsDir.deleteRecursively()
        narration.deleteIfExists()
        finish(job, output)
    }

    private fun attach(
        job: Job,
        script: StoryScript,
    ) {
        job.script = script
        if (script.degraded) job.degraded = true
        job.notes += script.notes
    }

    private fun degrade(
        job: Job,
        notes: List<String>,
    ) {
        job.degraded = true
        job.notes += notes
    }

    /** Voice clips (bounded parallel TTS processes) and, for illustrated skills, pictures — side by side. */
    private fun generateAssets(
        job: Job,
        script: StoryScript,
        scenes: List<Scene>,
        clipsDir: Path,
    ): Pair<List<Path>, SceneImages?> =
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val pictures: Future<SceneImages>? =
                if (job.spec.skill.isIllustrated) executor.submit<SceneImages> { images.generate(script, job.spec.skill) } else null

            val clips = scenes.map { clipsDir.resolve(String.format(Locale.ROOT, "scene-%03d.wav", it.index)) }
            tts.synthesizeAll(
                scenes.zip(clips) { scene, clip ->
                    SpeechRequest(scene.text, job.spec.voice, job.spec.wordsPerMinute, scene.estimatedSeconds, clip)
                },
            )
            job.progress = 35
            clips to pictures?.get()
        }

    /**
     * How long the director may take in a one-shot job: the delivery target minus time already
     * spent (e.g. queued) minus what assets and rendering are expected to need on this machine.
     */
    private fun directorBudget(job: Job): java.time.Duration {
        val spec = job.spec
        val words = requireNotNull(spec.text).split(Regex("\\s+")).count { it.isNotBlank() }.coerceAtMost(spec.skill.maxWords)
        val videoSeconds =
            (words * 60.0 / spec.wordsPerMinute + spec.skill.scenes.max * props.breakdown.audioPaddingSeconds)
                .coerceAtMost(props.sla.maxVideoSeconds)
        val renderMs = videoSeconds * spec.format.fps * stats.renderMsPerFrame
        val elapsedMs = Duration.between(job.createdAt, Instant.now()).toMillis()
        val budgetMs = props.sla.deliverySeconds * 1000 - elapsedMs - renderMs - stats.assetsMs - SAFETY_MARGIN_MS
        val budget = Duration.ofMillis(budgetMs.toLong().coerceIn(MIN_DIRECTOR_MS, props.llm.timeout.toMillis()))
        log.info(
            "Job {} director budget {} ms (est. {} s video: render {} ms, assets {} ms, already {} ms)",
            job.id,
            budget.toMillis(),
            videoSeconds.toInt(),
            renderMs.toLong(),
            stats.assetsMs.toLong(),
            elapsedMs,
        )
        return budget
    }

    private fun rendererFactory(
        job: Job,
        script: StoryScript,
        timeline: Timeline,
        pictures: SceneImages?,
        speechSeconds: List<Double>,
    ): () -> SceneRenderer {
        val spec = job.spec
        val skill = spec.skill
        if (!skill.isIllustrated) {
            val blocks = timeline.entries.map { textLayout.layout(script.scenes[it.index].narration, spec.textScroll) }
            return {
                TextScrollRenderer(
                    spec.textScroll,
                    LinearScroll(spec.textScroll.startY, spec.textScroll.scrollSpeed),
                    timeline,
                    blocks,
                )
            }
        }
        val illustrated =
            timeline.entries.map {
                val scene = script.scenes[it.index]
                IllustratedScene(
                    image = requireNotNull(pictures).images[it.index],
                    camera = scene.camera,
                    captions = SceneCaptions.of(scene.narration, skill.caption.maxWords, speechSeconds[it.index]),
                )
            }
        val prepared = IllustratedRenderer.prepare(illustrated, spec.format)
        return { IllustratedRenderer(spec.format, skill.caption, skill.transitionSeconds, timeline, prepared) }
    }

    private fun finish(
        job: Job,
        output: Path,
    ) {
        val total = Duration.between(job.createdAt, Instant.now())
        job.timingsMs["total"] = total.toMillis()
        job.slaMet = total.toMillis() <= props.sla.deliverySeconds * 1000
        if (job.slaMet == false) {
            log.warn("Job {} missed the {} s delivery target: {} ms {}", job.id, props.sla.deliverySeconds, total.toMillis(), job.timingsMs)
        }
        job.complete(output)
    }

    private inline fun <T> timed(
        job: Job,
        stage: String,
        block: () -> T,
    ): T {
        val started = System.nanoTime()
        try {
            return block()
        } finally {
            job.timingsMs[stage] = (System.nanoTime() - started) / 1_000_000
        }
    }

    private companion object {
        /** Never give the director less than this, or it could not answer even on fast hardware. */
        const val MIN_DIRECTOR_MS = 4_000L

        /** Slack for probing, audio assembly and scheduling noise. */
        const val SAFETY_MARGIN_MS = 3_000.0
    }
}
