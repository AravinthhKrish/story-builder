package com.lucy.storybuilder.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path
import java.time.Duration

@ConfigurationProperties("storybuilder")
data class StoryBuilderProperties(
    val workDir: Path = Path.of("./work"),
    val ffmpegPath: String = "ffmpeg",
    val ffprobePath: String = "ffprobe",
    val maxConcurrentJobs: Int = 2,
    /** Threads drawing video frames per job; 0 = auto (cores - 1, at most 4). */
    val renderThreads: Int = 0,
    val maxTextLength: Int = 20_000,
    /** Fail startup when ffmpeg/ffprobe/TTS binaries are missing. */
    val verifyToolchain: Boolean = true,
    /** Style skill used when a request doesn't name one (see skills/). */
    val defaultSkill: String = "cartoon-storybook",
    val tts: Tts = Tts(),
    val breakdown: Breakdown = Breakdown(),
    val render: Render = Render(),
    val skills: Skills = Skills(),
    val llm: Llm = Llm(),
    val images: Images = Images(),
    val security: Security = Security(),
    val sla: Sla = Sla(),
) {
    data class Skills(
        /** Extra skill folders loaded on top of the bundled ones; a same-named skill here overrides a bundled one. */
        val dir: Path? = null,
    )

    enum class LlmProvider { OLLAMA, OPENAI_COMPATIBLE }

    /** The scene director. Ollama by default; any OpenAI-compatible chat endpoint also works. */
    data class Llm(
        val provider: LlmProvider = LlmProvider.OLLAMA,
        val baseUrl: String = "http://localhost:11434",
        val model: String = "llama3.2:3b",
        /** Sent as `Authorization: Bearer …` when set (OpenAI-compatible hosts). */
        val apiKey: String? = null,
        /** Upper bound per call; one-shot jobs may give the director less, to protect the delivery target. */
        val timeout: Duration = Duration.ofSeconds(30),
        val temperature: Double = 0.4,
        /** Hard cap on generated tokens, so a rambling model can't blow the time budget. */
        val maxOutputTokens: Int = 900,
        /** Ollama keeps the model in memory this long after a call, avoiding a cold load per request. */
        val keepAlive: String = "30m",
    )

    enum class ImageProviderType { AUTO, FAL, PLACEHOLDER }

    data class Images(
        /** AUTO = fal.ai when a key is configured, otherwise offline placeholder art. */
        val provider: ImageProviderType = ImageProviderType.AUTO,
        /** Scene images requested at once. */
        val concurrency: Int = 6,
        val timeout: Duration = Duration.ofSeconds(20),
        val retries: Int = 1,
        val fal: Fal = Fal(),
    )

    data class Fal(
        val baseUrl: String = "https://fal.run",
        val model: String = "fal-ai/flux/schnell",
        /** From the FAL_KEY environment variable. Never logged. */
        val apiKey: String? = null,
        /** Inline the image in the response (data URI), saving a second download round trip. */
        val syncMode: Boolean = true,
    )

    data class Security(
        /** Accepted values of the X-API-Key header on /api/ requests. Empty = authentication disabled. */
        val apiKeys: List<String> = emptyList(),
    )

    /** Shorts limits: the video is at most [maxVideoSeconds] long and ready within [deliverySeconds]. */
    data class Sla(
        val maxVideoSeconds: Double = 60.0,
        val deliverySeconds: Long = 60,
        /** Narration may be sped up by at most this factor to fit; beyond it, trailing scenes are dropped. */
        val maxSpeedup: Double = 1.25,
    )

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
        /** Interpreter of the venv piper-tts is installed in; blank = the `python` next to [binary]. */
        val python: String? = null,
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
