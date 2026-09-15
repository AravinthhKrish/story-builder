package com.lucy.storybuilder.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path

@ConfigurationProperties("storybuilder")
data class StoryBuilderProperties(
    val workDir: Path = Path.of("./work"),
    val ffmpegPath: String = "ffmpeg",
    val ffprobePath: String = "ffprobe",
    val maxConcurrentJobs: Int = 2,
    val maxTextLength: Int = 20_000,
    /** Fail startup when ffmpeg/ffprobe/TTS binaries are missing. */
    val verifyToolchain: Boolean = true,
    val tts: Tts = Tts(),
    val breakdown: Breakdown = Breakdown(),
    val render: Render = Render(),
) {
    /** AUTO picks the first engine that works here: SAY (macOS), then PIPER, then ESPEAK. */
    enum class TtsEngineType { AUTO, SAY, PIPER, ESPEAK, SILENT }

    /** Each engine has its own default voice; a request's `options.voice` overrides it. */
    data class Tts(
        val engine: TtsEngineType = TtsEngineType.AUTO,
        val say: Say = Say(),
        val piper: Piper = Piper(),
        val espeak: Espeak = Espeak(),
    )

    data class Say(
        /** Blank = the system voice. */
        val voice: String? = null,
    )

    data class Piper(
        val binary: String = "piper",
        /** Voice model name; `<dataDir>/<voice>.onnx` (+ `.onnx.json`) must exist. */
        val voice: String = "en_US-lessac-medium",
        val dataDir: Path = Path.of("./voices"),
        /** The model's natural pace, used to turn a words-per-minute request into Piper's length scale. */
        val nativeWordsPerMinute: Int = 210,
    )

    data class Espeak(
        val binary: String = "espeak-ng",
        val voice: String = "en-us",
    )

    data class Breakdown(
        val wordsPerMinute: Int = 150,
        val minSceneSeconds: Double = 3.0,
        val maxWordsPerScene: Int = 40,
        /** Silence appended after each scene's narration before the next scene starts. */
        val audioPaddingSeconds: Double = 0.5,
    )

    data class Render(
        val width: Int = 1920,
        val height: Int = 1080,
        val fps: Int = 24,
        val startY: Double = 500.0,
        val scrollSpeed: Double = 15.0,
        val backgroundColor: String = "#101826",
        val textColor: String = "#F5F1E6",
        val fontName: String = "SansSerif",
        val fontSize: Int = 64,
        val margin: Int = 160,
        val fadeSeconds: Double = 0.4,
    )
}
