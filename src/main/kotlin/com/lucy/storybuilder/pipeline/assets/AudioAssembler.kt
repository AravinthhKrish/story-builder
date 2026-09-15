package com.lucy.storybuilder.pipeline.assets

import com.lucy.storybuilder.process.Ffmpeg
import com.lucy.storybuilder.process.ffSeconds
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * Builds the single narration track: every scene clip is padded with silence (or trimmed) to its
 * exact timeline slot, then all slots are concatenated — so scene N's voice starts on scene N's
 * first video frame. Done in one ffmpeg call over PCM, which avoids the drift mp3 frame padding adds.
 * [speed] > 1 speeds narration up (pitch preserved) to fit the Shorts length limit.
 */
@Component
class AudioAssembler(
    private val ffmpeg: Ffmpeg,
) {
    fun assemble(
        clips: List<Path>,
        slotSeconds: List<Double>,
        output: Path,
        speed: Double = 1.0,
    ) {
        require(clips.isNotEmpty() && clips.size == slotSeconds.size) { "Need one timeline slot per clip" }
        require(speed in 0.5..2.0) { "speed must be within 0.5..2.0" }

        val args = mutableListOf<String>()
        clips.forEach { args += listOf("-i", it.toString()) }
        val tempo = if (speed != 1.0) "atempo=${String.format(java.util.Locale.ROOT, "%.4f", speed)}," else ""

        val filter =
            buildString {
                slotSeconds.forEachIndexed { i, seconds ->
                    val d = ffSeconds(seconds)
                    append("[$i:a]aresample=48000,aformat=sample_fmts=s16:channel_layouts=mono,$tempo")
                    append("apad=whole_dur=$d,atrim=duration=$d,asetpts=PTS-STARTPTS[a$i];")
                }
                clips.indices.forEach { append("[a$it]") }
                append("concat=n=${clips.size}:v=0:a=1[out]")
            }

        args += listOf("-filter_complex", filter, "-map", "[out]", "-c:a", "pcm_s16le", output.toString())
        ffmpeg.run(*args.toTypedArray())
    }
}
