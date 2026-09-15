package com.lucy.storybuilder.pipeline.assets.tts

import com.lucy.storybuilder.config.StoryBuilderProperties
import com.lucy.storybuilder.process.Ffmpeg
import com.lucy.storybuilder.process.ProcessRunner
import java.util.Locale
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.writeText

/**
 * Piper (github.com/OHF-Voice/piper1-gpl): neural, natural-sounding, fully offline. Installed with
 * `pip install piper-tts`; voice models (`<voice>.onnx` + `.onnx.json`) live in `dataDir`.
 */
class PiperTtsEngine(
    private val config: StoryBuilderProperties.Piper,
    private val ffmpeg: Ffmpeg,
    private val processRunner: ProcessRunner,
) : TtsEngine {
    override val name = "piper"

    override fun synthesize(request: SpeechRequest) {
        val dir = request.output.parent
        val base = request.output.nameWithoutExtension
        val textFile = dir.resolve("$base.txt").apply { writeText(request.text) }
        val raw = dir.resolve("$base.piper.wav")
        try {
            processRunner.run(
                listOf(
                    config.binary,
                    "--model",
                    request.voice ?: config.voice,
                    "--data-dir",
                    config.dataDir.toString(),
                    "--input-file",
                    textFile.toString(),
                    "--output-file",
                    raw.toString(),
                    "--length-scale",
                    lengthScale(request.wordsPerMinute),
                    "--sentence-silence",
                    "0.25",
                ),
            )
            ffmpeg.toCanonicalWav(raw, request.output)
        } finally {
            raw.deleteIfExists()
            textFile.deleteIfExists()
        }
    }

    /** Piper controls pace by phoneme length: > 1 is slower. Clamped to keep speech intelligible. */
    private fun lengthScale(wordsPerMinute: Int): String =
        String.format(Locale.ROOT, "%.3f", (config.nativeWordsPerMinute.toDouble() / wordsPerMinute).coerceIn(0.5, 2.0))

    override fun checkAvailable() {
        processRunner.probe(listOf(config.binary, "--help"), "install with: pip install piper-tts")
        val model = config.dataDir.resolve("${config.voice}.onnx")
        check(model.exists()) {
            "voice model $model is missing (download with: python3 -m piper.download_voices ${config.voice} " +
                "--data-dir ${config.dataDir})"
        }
    }
}
