package com.lucy.storybuilder.pipeline.assets.tts

import com.lucy.storybuilder.process.Ffmpeg
import com.lucy.storybuilder.process.ffSeconds

/** Produces silence of the estimated scene length: for tests, CI, or machines without a TTS engine. */
class SilentTtsEngine(
    private val ffmpeg: Ffmpeg,
) : TtsEngine {
    override val name = "silent"

    override fun synthesize(request: SpeechRequest) {
        ffmpeg.run(
            "-f",
            "lavfi",
            "-i",
            "anullsrc=r=48000:cl=mono",
            "-t",
            ffSeconds(request.estimatedSeconds),
            "-c:a",
            "pcm_s16le",
            request.output.toString(),
        )
    }

    override fun checkAvailable() = Unit
}
