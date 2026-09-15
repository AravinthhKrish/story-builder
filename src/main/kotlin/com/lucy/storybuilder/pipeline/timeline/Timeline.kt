package com.lucy.storybuilder.pipeline.timeline

import org.springframework.stereotype.Component
import kotlin.math.ceil

/** Scene [index]'s slot on the frame grid. */
data class TimelineEntry(
    val index: Int,
    val startFrame: Int,
    val frameCount: Int,
)

/** Scenes laid end to end on a frame grid. Durations are whole frames so audio and video cut together. */
class Timeline(
    val fps: Int,
    val entries: List<TimelineEntry>,
) {
    val totalFrames: Int = entries.sumOf { it.frameCount }
    val totalSeconds: Double get() = totalFrames.toDouble() / fps

    fun seconds(entry: TimelineEntry): Double = entry.frameCount.toDouble() / fps

    /** Seconds since [entry] started, at [frame]. */
    fun sceneTime(
        entry: TimelineEntry,
        frame: Int,
    ): Double = (frame - entry.startFrame).toDouble() / fps

    /** The scene showing on [frame] (binary search over start frames). */
    fun entryAt(frame: Int): TimelineEntry {
        require(frame in 0 until totalFrames) { "frame $frame outside 0..<$totalFrames" }
        var lo = 0
        var hi = entries.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (entries[mid].startFrame <= frame) lo = mid else hi = mid - 1
        }
        return entries[lo]
    }
}

/** Stage 3 — places scenes back to back, rounding each duration up to a whole frame. */
@Component
class TimelineBuilder {
    fun build(
        durationsSeconds: List<Double>,
        fps: Int,
    ): Timeline {
        var start = 0
        val entries =
            durationsSeconds.mapIndexed { i, seconds ->
                // Tolerance keeps an exact 2.0 s at 24 fps at 48 frames instead of 49 from float noise.
                val frames = ceil(seconds * fps - 1e-6).toInt().coerceAtLeast(1)
                TimelineEntry(i, start, frames).also { start += frames }
            }
        return Timeline(fps, entries)
    }
}
