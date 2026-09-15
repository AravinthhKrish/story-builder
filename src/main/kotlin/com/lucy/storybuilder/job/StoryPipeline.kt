package com.lucy.storybuilder.job

import com.lucy.storybuilder.config.StoryBuilderProperties
import com.lucy.storybuilder.pipeline.assets.AudioAssembler
import com.lucy.storybuilder.pipeline.assets.TextLayout
import com.lucy.storybuilder.pipeline.assets.tts.SpeechRequest
import com.lucy.storybuilder.pipeline.assets.tts.TtsEngine
import com.lucy.storybuilder.pipeline.breakdown.StoryBreakdownService
import com.lucy.storybuilder.pipeline.render.FfmpegEncoder
import com.lucy.storybuilder.pipeline.render.FrameRenderer
import com.lucy.storybuilder.pipeline.timeline.LinearScroll
import com.lucy.storybuilder.pipeline.timeline.TimelineBuilder
import com.lucy.storybuilder.process.Ffmpeg
import org.springframework.stereotype.Service
import java.util.Locale
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively

/** Runs the four stages from docs/PIPELINE.md for one job, reporting stage and progress as it goes. */
@Service
class StoryPipeline(
    private val props: StoryBuilderProperties,
    private val breakdown: StoryBreakdownService,
    private val tts: TtsEngine,
    private val ffmpeg: Ffmpeg,
    private val textLayout: TextLayout,
    private val timelineBuilder: TimelineBuilder,
    private val audioAssembler: AudioAssembler,
    private val encoder: FfmpegEncoder,
) {
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun run(job: Job) {
        val spec = job.spec
        val dir = props.workDir.resolve(job.id.toString()).createDirectories()

        // 1. Story breakdown
        job.advance(JobStatus.BREAKDOWN, 0)
        val scenes = breakdown.breakdown(spec.text, spec.wordsPerMinute)
        check(scenes.isNotEmpty()) { "Story contains no text" }
        job.scenes = scenes.map { SceneResult(it) }

        // 2. Asset generation: narration per scene + wrapped text blocks
        job.advance(JobStatus.ASSETS, 5)
        val clipsDir = dir.resolve("scenes").createDirectories()
        val clips =
            scenes.map { scene ->
                val clip = clipsDir.resolve(String.format(Locale.ROOT, "scene-%03d.wav", scene.index))
                tts.synthesize(SpeechRequest(scene.text, spec.voice, spec.wordsPerMinute, scene.estimatedSeconds, clip))
                job.progress = 5 + 30 * (scene.index + 1) / scenes.size
                clip
            }
        // The slot is the reading-speed estimate, stretched if the narration runs longer.
        val durations =
            scenes.zip(clips) { scene, clip ->
                maxOf(scene.estimatedSeconds, ffmpeg.probeDurationSeconds(clip) + props.breakdown.audioPaddingSeconds)
            }
        val blocks = scenes.map { textLayout.layout(it.text, spec.render) }

        // 3. Timeline layout & motion math
        job.advance(JobStatus.LAYOUT, 35)
        val timeline = timelineBuilder.build(scenes, blocks, durations, spec.render.fps)
        job.scenes = timeline.entries.map { SceneResult(it.scene, timeline.seconds(it)) }
        job.durationSeconds = timeline.totalSeconds

        // 4. Video rendering: bind audio to the timeline, then stream frames into ffmpeg
        job.advance(JobStatus.RENDERING, 40)
        val narration = dir.resolve("narration.wav")
        audioAssembler.assemble(clips, timeline.entries.map(timeline::seconds), narration)

        val output = dir.resolve("output.mp4")
        FrameRenderer(spec.render, LinearScroll(spec.render.startY, spec.render.scrollSpeed)).use { renderer ->
            encoder.encode(
                settings = spec.render,
                totalFrames = timeline.totalFrames,
                narration = narration,
                output = output,
                frameAt = { renderer.render(timeline, it) },
                onFrame = { job.progress = 40 + 59 * (it + 1) / timeline.totalFrames },
            )
        }

        clipsDir.deleteRecursively()
        narration.deleteIfExists()
        job.complete(output)
    }
}
