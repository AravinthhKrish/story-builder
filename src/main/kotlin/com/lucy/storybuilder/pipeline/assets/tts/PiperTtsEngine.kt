package com.lucy.storybuilder.pipeline.assets.tts

import com.lucy.storybuilder.config.StoryBuilderProperties
import com.lucy.storybuilder.process.Ffmpeg
import com.lucy.storybuilder.process.ProcessRunner
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.writeText

/**
 * Piper (github.com/OHF-Voice/piper1-gpl): neural, natural-sounding, fully offline. Installed with
 * `pip install piper-tts`; voice models (`<voice>.onnx` + `.onnx.json`) live in `dataDir`.
 *
 * A whole job is synthesized by one Python process that loads the voice once (`piper_batch.py`);
 * the per-scene CLI is only the fallback. On small hosts that is the difference between ~30 s and
 * a few seconds of narration time.
 */
class PiperTtsEngine(
    private val config: StoryBuilderProperties.Piper,
    private val ffmpeg: Ffmpeg,
    private val processRunner: ProcessRunner,
) : TtsEngine {
    override val name = "piper"
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = JsonMapper.builder().build()

    /** The venv's interpreter next to the piper binary, or whatever `python3` is on PATH. */
    private val python: String =
        config.python?.takeIf { it.isNotBlank() }
            ?: Path
                .of(config.binary)
                .parent
                ?.resolve("python")
                ?.takeIf { Files.isExecutable(it) }
                ?.toString()
            ?: which("python")
            ?: "python3"

    private val batchScript: Path by lazy {
        Files.createTempFile("piper_batch", ".py").also { file ->
            javaClass.getResourceAsStream("/tts/piper_batch.py").use { input ->
                Files.copy(
                    requireNotNull(input) { "piper_batch.py missing from the classpath" },
                    file,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }
            file.toFile().deleteOnExit()
        }
    }

    override fun synthesizeAll(requests: List<SpeechRequest>) {
        if (requests.isEmpty()) return
        val batches = requests.groupBy { Pair(it.voice ?: config.voice, lengthScale(it.wordsPerMinute)) }
        for ((key, batch) in batches) {
            try {
                synthesizeBatch(key.first, key.second, batch)
            } catch (e: Exception) {
                log.warn("Piper batch synthesis failed ({}); falling back to one process per scene", e.message)
                batch.forEach(::synthesize)
            }
        }
    }

    private fun synthesizeBatch(
        voice: String,
        lengthScale: Double,
        batch: List<SpeechRequest>,
    ) {
        val jobsFile = batch.first().output.resolveSibling("piper-jobs.json")
        val spec =
            mapOf(
                "model" to config.dataDir.resolve("$voice.onnx").toString(),
                "length_scale" to lengthScale,
                "jobs" to batch.map { mapOf("text" to it.text, "output" to it.output.toString()) },
            )
        try {
            jobsFile.writeText(json.writeValueAsString(spec))
            processRunner.run(listOf(python, batchScript.toString(), jobsFile.toString()))
        } finally {
            jobsFile.deleteIfExists()
        }
        // Piper writes 22.05 kHz mono PCM; the audio assembler resamples, so no conversion pass is needed.
        batch.forEach { check(it.output.exists()) { "Piper produced no audio for ${it.output.fileName}" } }
    }

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
                    String.format(Locale.ROOT, "%.3f", lengthScale(request.wordsPerMinute)),
                ),
            )
            ffmpeg.toCanonicalWav(raw, request.output)
        } finally {
            raw.deleteIfExists()
            textFile.deleteIfExists()
        }
    }

    /** Piper controls pace by phoneme length: > 1 is slower. Clamped to keep speech intelligible. */
    private fun lengthScale(wordsPerMinute: Int): Double = (config.nativeWordsPerMinute.toDouble() / wordsPerMinute).coerceIn(0.5, 2.0)

    override fun checkAvailable() {
        processRunner.probe(listOf(config.binary, "--help"), "install with: pip install piper-tts")
        val model = config.dataDir.resolve("${config.voice}.onnx")
        check(model.exists()) {
            "voice model $model is missing (download with: python3 -m piper.download_voices ${config.voice} " +
                "--data-dir ${config.dataDir})"
        }
    }

    private fun which(program: String): String? =
        System
            .getenv("PATH")
            ?.split(java.io.File.pathSeparator)
            ?.map { Path.of(it, program) }
            ?.firstOrNull { Files.isExecutable(it) }
            ?.toString()
}
