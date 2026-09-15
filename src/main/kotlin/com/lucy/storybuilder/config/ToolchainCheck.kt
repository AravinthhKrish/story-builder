package com.lucy.storybuilder.config

import com.lucy.storybuilder.ai.llm.LlmClient
import com.lucy.storybuilder.pipeline.assets.tts.TtsEngine
import com.lucy.storybuilder.process.ProcessRunner
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import java.io.IOException

/**
 * Fails startup with a clear fix when a required binary is missing, instead of failing the first job.
 * The director LLM is only warned about: without it, illustrated scripts fall back to rule-based scenes.
 */
@Component
class ToolchainCheck(
    private val props: StoryBuilderProperties,
    private val processRunner: ProcessRunner,
    private val tts: TtsEngine,
    private val llm: LlmClient,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        if (!props.verifyToolchain) return

        val version = runTool(props.ffmpegPath, "-hide_banner", "-version").lineSequence().first()
        runTool(props.ffprobePath, "-hide_banner", "-version")
        check("libx264" in runTool(props.ffmpegPath, "-hide_banner", "-encoders")) {
            "ffmpeg at '${props.ffmpegPath}' was built without libx264; install a full build (brew install ffmpeg)"
        }
        tts.checkAvailable()
        log.info("Toolchain OK: {} | TTS engine: {}", version, tts.name)

        val llmProblem = llm.problem()
        if (llmProblem == null) {
            log.info("Director LLM OK: {}", llm.description)
        } else {
            log.warn("Director LLM unavailable, illustrated scripts will use the rule-based fallback: {}", llmProblem)
        }
    }

    private fun runTool(vararg command: String): String =
        try {
            processRunner.run(command.toList()).stdout
        } catch (e: IOException) {
            throw IllegalStateException(
                "'${command.first()}' could not be started (${e.message}). Install FFmpeg (brew install ffmpeg) " +
                    "or point storybuilder.ffmpeg-path / storybuilder.ffprobe-path at the binaries.",
                e,
            )
        }
}
