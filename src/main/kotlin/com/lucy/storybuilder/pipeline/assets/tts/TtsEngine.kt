package com.lucy.storybuilder.pipeline.assets.tts

import com.lucy.storybuilder.config.StoryBuilderProperties
import com.lucy.storybuilder.config.StoryBuilderProperties.TtsEngineType
import com.lucy.storybuilder.process.Ffmpeg
import com.lucy.storybuilder.process.ProcessRunner
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.IOException
import java.nio.file.Path

/** Stage 2 (audio layer): renders one scene's narration to a 48 kHz mono PCM WAV file. */
interface TtsEngine {
    val name: String

    fun synthesize(request: SpeechRequest)

    /** Throws with an actionable message when the engine cannot run on this machine. */
    fun checkAvailable()
}

data class SpeechRequest(
    val text: String,
    /** Null means the engine's configured default voice. */
    val voice: String?,
    val wordsPerMinute: Int,
    /** Used by engines that cannot measure speech themselves (e.g. [SilentTtsEngine]). */
    val estimatedSeconds: Double,
    val output: Path,
)

@Configuration
class TtsConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun ttsEngine(
        props: StoryBuilderProperties,
        ffmpeg: Ffmpeg,
        processRunner: ProcessRunner,
    ): TtsEngine {
        val say = { MacSayTtsEngine(props.tts.say, ffmpeg, processRunner) }
        val piper = { PiperTtsEngine(props.tts.piper, ffmpeg, processRunner) }
        val espeak = { EspeakTtsEngine(props.tts.espeak, ffmpeg, processRunner) }
        return when (props.tts.engine) {
            TtsEngineType.SAY -> say()
            TtsEngineType.PIPER -> piper()
            TtsEngineType.ESPEAK -> espeak()
            TtsEngineType.SILENT -> SilentTtsEngine(ffmpeg)
            TtsEngineType.AUTO -> autoDetect(listOf(say, piper, espeak))
        }
    }

    private fun autoDetect(candidates: List<() -> TtsEngine>): TtsEngine {
        val problems = mutableListOf<String>()
        for (candidate in candidates) {
            val engine = candidate()
            try {
                engine.checkAvailable()
                log.info("TTS engine auto-detected: {}", engine.name)
                return engine
            } catch (e: IllegalStateException) {
                problems += "${engine.name}: ${e.message}"
            }
        }
        error(
            "No TTS engine available (${problems.joinToString("; ")}). Install Piper or espeak-ng, " +
                "or set storybuilder.tts.engine=silent.",
        )
    }
}

/** Converts any audio file ffmpeg can read into the pipeline's canonical WAV format. */
internal fun Ffmpeg.toCanonicalWav(
    input: Path,
    output: Path,
) {
    run("-i", input.toString(), "-ar", "48000", "-ac", "1", "-c:a", "pcm_s16le", output.toString())
}

/** Runs a probe command, turning "binary not found" into an [IllegalStateException] with [hint]. */
internal fun ProcessRunner.probe(
    command: List<String>,
    hint: String,
): String =
    try {
        run(command).stdout
    } catch (e: IOException) {
        throw IllegalStateException("'${command.first()}' not found ($hint)", e)
    } catch (e: RuntimeException) {
        throw IllegalStateException("'${command.first()}' is not working: ${e.message} ($hint)", e)
    }
