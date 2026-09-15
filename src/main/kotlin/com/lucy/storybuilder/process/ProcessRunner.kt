package com.lucy.storybuilder.process

import org.springframework.stereotype.Component
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration
import java.util.concurrent.TimeUnit

class ProcessFailedException(
    val command: List<String>,
    val exitCode: Int?,
    val stderr: String,
) : RuntimeException(
        buildString {
            append("`${command.first()}` ")
            append(if (exitCode == null) "timed out" else "exited with code $exitCode")
            if (stderr.isNotBlank()) {
                append(": ").append(
                    stderr
                        .trim()
                        .lines()
                        .takeLast(5)
                        .joinToString(" | "),
                )
            }
        },
    )

data class ProcessResult(
    val stdout: String,
    val stderr: String,
)

/**
 * Runs external tools (ffmpeg, ffprobe, TTS engines) without a shell, so user text is never interpreted.
 *
 * stdout and stderr are drained on their own threads: a child that fills an undrained pipe blocks
 * forever, which is the classic way ffmpeg integrations deadlock.
 */
@Component
class ProcessRunner {
    fun run(
        command: List<String>,
        timeout: Duration = Duration.ofMinutes(10),
    ): ProcessResult {
        val process = ProcessBuilder(command).start()
        process.outputStream.close()
        val stdout = Drain.start(process.inputStream)
        val stderr = Drain.start(process.errorStream)
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw ProcessFailedException(command, null, stderr.await())
        }
        val result = ProcessResult(stdout.await(), stderr.await())
        if (process.exitValue() != 0) throw ProcessFailedException(command, process.exitValue(), result.stderr)
        return result
    }

    /** Starts a process whose stdin the caller streams into (e.g. raw video frames into ffmpeg). */
    fun startStreaming(command: List<String>): StreamingProcess {
        val process =
            ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
        return StreamingProcess(command, process, Drain.start(process.errorStream))
    }
}

class StreamingProcess internal constructor(
    private val command: List<String>,
    private val process: Process,
    private val stderr: Drain,
) {
    val stdin: OutputStream = process.outputStream.buffered(1 shl 20)

    /** Closes stdin (signalling end of input) and waits for a clean exit. */
    fun finish(timeout: Duration = Duration.ofMinutes(30)) {
        runCatching { stdin.close() }
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw ProcessFailedException(command, null, stderr.await())
        }
        if (process.exitValue() != 0) throw ProcessFailedException(command, process.exitValue(), stderr.await())
    }

    /** Kills the process and returns its failure, used when writing to stdin blew up mid-stream. */
    fun abort(): ProcessFailedException {
        runCatching { stdin.close() }
        val exited = process.waitFor(5, TimeUnit.SECONDS)
        if (!exited) process.destroyForcibly()
        return ProcessFailedException(command, if (exited) process.exitValue() else null, stderr.await())
    }
}

/** Reads a stream to the end on a virtual thread, keeping at most [limit] trailing characters. */
internal class Drain private constructor(
    stream: InputStream,
    private val limit: Int,
) {
    private val buffer = StringBuilder()
    private val thread =
        Thread.ofVirtual().start {
            stream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    buffer.append(line).append('\n')
                    if (buffer.length > limit) buffer.delete(0, buffer.length - limit)
                }
            }
        }

    fun await(): String {
        thread.join()
        return buffer.toString()
    }

    companion object {
        fun start(
            stream: InputStream,
            limit: Int = 64 * 1024,
        ) = Drain(stream, limit)
    }
}
