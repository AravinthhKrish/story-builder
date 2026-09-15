package com.lucy.storybuilder.pipeline.timeline

import com.lucy.storybuilder.pipeline.assets.TextBlock
import com.lucy.storybuilder.pipeline.breakdown.Scene
import org.springframework.stereotype.Component
import kotlin.math.ceil

data class TimelineEntry(
    val scene: Scene,
    val block: TextBlock,
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
        scenes: List<Scene>,
        blocks: List<TextBlock>,
        durationsSeconds: List<Double>,
        fps: Int,
    ): Timeline {
        require(scenes.size == blocks.size && scenes.size == durationsSeconds.size) { "scenes, blocks and durations must align" }
        var start = 0
        val entries =
            scenes.indices.map { i ->
                // Tolerance keeps an exact 2.0 s at 24 fps at 48 frames instead of 49 from float noise.
                val frames = ceil(durationsSeconds[i] * fps - 1e-6).toInt().coerceAtLeast(1)
                TimelineEntry(scenes[i], blocks[i], start, frames).also { start += frames }
            }
        return Timeline(fps, entries)
    }
}
