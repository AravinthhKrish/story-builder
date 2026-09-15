package com.lucy.storybuilder.pipeline.assets.tts

import com.lucy.storybuilder.config.StoryBuilderProperties
import com.lucy.storybuilder.process.Ffmpeg
import com.lucy.storybuilder.process.ProcessRunner
import kotlin.io.path.deleteIfExists
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.writeText

/** espeak-ng: tiny, fast, available on every Linux distro (`apt install espeak-ng`). Robotic but reliable. */
class EspeakTtsEngine(
    private val config: StoryBuilderProperties.Espeak,
    private val ffmpeg: Ffmpeg,
    private val processRunner: ProcessRunner,
) : TtsEngine {
    override val name = "espeak-ng"

    override fun synthesize(request: SpeechRequest) {
        val dir = request.output.parent
        val base = request.output.nameWithoutExtension
        val textFile = dir.resolve("$base.txt").apply { writeText(request.text) }
        val raw = dir.resolve("$base.espeak.wav")
        try {
            processRunner.run(
                listOf(
                    config.binary,
                    "-v",
                    request.voice ?: config.voice,
                    "-s",
                    request.wordsPerMinute.toString(),
                    "-f",
                    textFile.toString(),
                    "-w",
                    raw.toString(),
                ),
            )
            ffmpeg.toCanonicalWav(raw, request.output)
        } finally {
            raw.deleteIfExists()
            textFile.deleteIfExists()
        }
    }

    override fun checkAvailable() {
        processRunner.probe(listOf(config.binary, "--version"), "install with: apt-get install espeak-ng")
    }
}
