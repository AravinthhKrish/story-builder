package com.lucy.storybuilder.process

import com.lucy.storybuilder.config.StoryBuilderProperties
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.util.Locale

/** Seconds as ffmpeg expects them: always a '.' decimal point, whatever the JVM locale. */
fun ffSeconds(seconds: Double): String = String.format(Locale.ROOT, "%.4f", seconds)

/** Thin wrapper over the ffmpeg / ffprobe binaries configured in [StoryBuilderProperties]. */
@Component
class Ffmpeg(
    private val props: StoryBuilderProperties,
    private val processRunner: ProcessRunner,
) {
    /** Runs ffmpeg non-interactively, overwriting outputs, logging errors only. */
    fun run(vararg args: String): ProcessResult = processRunner.run(command(args.toList()))

    fun command(args: List<String>): List<String> = listOf(props.ffmpegPath, "-hide_banner", "-nostdin", "-loglevel", "error", "-y") + args

    fun probeDurationSeconds(file: Path): Double {
        val out =
            processRunner.run(
                listOf(
                    props.ffprobePath,
                    "-v",
                    "error",
                    "-show_entries",
                    "format=duration",
                    "-of",
                    "default=noprint_wrappers=1:nokey=1",
                    file.toString(),
                ),
            )
        return out.stdout.trim().toDoubleOrNull()
            ?: error("ffprobe could not read a duration from $file: '${out.stdout.trim()}'")
    }

    /** Stream-level metadata as `key=value` pairs, one map per stream (used for verification). */
    fun probeStreams(file: Path): List<Map<String, String>> {
        val out =
            processRunner.run(
                listOf(
                    props.ffprobePath,
                    "-v",
                    "error",
                    "-show_entries",
                    "stream=codec_type,codec_name,width,height,r_frame_rate,pix_fmt",
                    "-of",
                    "default=noprint_wrappers=0",
                    file.toString(),
                ),
            )
        return out.stdout
            .split("[/STREAM]")
            .map { block ->
                block
                    .lines()
                    .map { it.trim() }
                    .filter { '=' in it }
                    .associate { it.substringBefore('=') to it.substringAfter('=') }
            }.filter { it.isNotEmpty() }
    }
}
