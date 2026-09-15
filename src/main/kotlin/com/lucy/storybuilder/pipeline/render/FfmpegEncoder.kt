package com.lucy.storybuilder.pipeline.render

import com.lucy.storybuilder.pipeline.VideoFormat
import com.lucy.storybuilder.process.Ffmpeg
import com.lucy.storybuilder.process.ProcessRunner
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Path

/**
 * Stage 4 (multiplexing + encoding): one ffmpeg process reads raw BGR frames on stdin, muxes in the
 * narration WAV, and writes an H.264/AAC .mp4 (yuv420p + faststart so every player and browser can
 * stream it).
 */
@Component
class FfmpegEncoder(
    private val ffmpeg: Ffmpeg,
    private val processRunner: ProcessRunner,
) {
    fun encode(
        format: VideoFormat,
        totalFrames: Int,
        narration: Path?,
        output: Path,
        frameAt: (Int) -> ByteArray,
        onFrame: (Int) -> Unit = {},
    ) {
        val args =
            buildList {
                addAll(listOf("-f", "rawvideo", "-pix_fmt", "bgr24", "-s", "${format.width}x${format.height}"))
                addAll(listOf("-r", format.fps.toString(), "-i", "-"))
                narration?.let { addAll(listOf("-i", it.toString(), "-map", "0:v", "-map", "1:a")) }
                addAll(listOf("-c:v", "libx264", "-preset", format.preset, "-crf", format.crf.toString(), "-pix_fmt", "yuv420p"))
                narration?.let { addAll(listOf("-c:a", "aac", "-b:a", "128k", "-shortest")) }
                addAll(listOf("-movflags", "+faststart", output.toString()))
            }

        val process = processRunner.startStreaming(ffmpeg.command(args))
        try {
            for (frame in 0 until totalFrames) {
                process.stdin.write(frameAt(frame))
                onFrame(frame)
            }
        } catch (e: IOException) {
            // Broken pipe: ffmpeg died mid-stream. Its stderr says why; that beats "Broken pipe".
            throw process.abort().apply { addSuppressed(e) }
        } catch (e: Throwable) {
            process.abort()
            throw e
        }
        process.finish()
    }
}
