package com.lucy.storybuilder.pipeline.render

import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Draws frames on several threads while handing them out strictly in order, so the (single)
 * ffmpeg stdin stays sequential. Java2D drawing is the bottleneck — x264 `veryfast` encodes several
 * times faster than one thread can draw — so this scales render time down by roughly [threads].
 *
 * Each worker thread owns its own renderer from [factory] (renderers are not thread-safe); a
 * bounded look-ahead window keeps memory at a few frames per thread.
 */
class ParallelFrameSource(
    private val totalFrames: Int,
    threads: Int,
    factory: () -> SceneRenderer,
) : AutoCloseable {
    private val created = Collections.synchronizedList(mutableListOf<SceneRenderer>())
    private val local = ThreadLocal.withInitial { factory().also { created += it } }
    private val executor =
        Executors.newFixedThreadPool(
            threads.coerceAtLeast(1),
            Thread
                .ofPlatform()
                .name("frame-", 1)
                .daemon(true)
                .factory(),
        )
    private val window = ArrayDeque<Future<ByteArray>>()
    private val lookAhead = threads.coerceAtLeast(1) * 2
    private var submitted = 0
    private var served = 0

    /** Must be called with 0, 1, 2, … in order. The returned array is owned by the caller. */
    fun frame(index: Int): ByteArray {
        check(index == served) { "frames must be read in order: expected $served, got $index" }
        while (submitted < totalFrames && submitted < index + lookAhead) {
            val f = submitted++
            // Copy: each renderer reuses one pixel buffer for every frame it draws.
            window.addLast(executor.submit<ByteArray> { local.get().render(f).copyOf() })
        }
        served++
        return window.removeFirst().get()
    }

    override fun close() {
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
        synchronized(created) { created.forEach { runCatching { it.close() } } }
    }
}
