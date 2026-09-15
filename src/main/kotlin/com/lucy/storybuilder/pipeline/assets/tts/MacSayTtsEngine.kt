package com.lucy.storybuilder.pipeline.assets.tts

import com.lucy.storybuilder.config.StoryBuilderProperties
import com.lucy.storybuilder.process.Ffmpeg
import com.lucy.storybuilder.process.ProcessRunner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.writeText

/** macOS built-in speech synthesizer (`/usr/bin/say`) — fully local, no install needed. */
class MacSayTtsEngine(
    private val config: StoryBuilderProperties.Say,
    private val ffmpeg: Ffmpeg,
    private val processRunner: ProcessRunner,
) : TtsEngine {
    override val name = "say"

    override fun synthesize(request: SpeechRequest) {
        val dir = request.output.parent
        val base = request.output.nameWithoutExtension
        val textFile = dir.resolve("$base.txt").apply { writeText(request.text) }
        val aiff = dir.resolve("$base.aiff")
        try {
            // Text goes in via -f so a scene starting with "-" can never be read as a flag.
            val command = mutableListOf(SAY, "-r", request.wordsPerMinute.toString(), "-f", textFile.toString(), "-o", aiff.toString())
            (request.voice ?: config.voice)?.takeIf { it.isNotBlank() }?.let { command.addAll(1, listOf("-v", it)) }
            processRunner.run(command)
            ffmpeg.toCanonicalWav(aiff, request.output)
        } finally {
            aiff.deleteIfExists()
            textFile.deleteIfExists()
        }
    }

    override fun checkAvailable() {
        check(Files.isExecutable(Path.of(SAY))) { "needs macOS ($SAY not found)" }
    }

    private companion object {
        const val SAY = "/usr/bin/say"
    }
}
