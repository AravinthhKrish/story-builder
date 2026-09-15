package com.lucy.storybuilder.pipeline.timeline

import com.lucy.storybuilder.pipeline.assets.TextBlock
import com.lucy.storybuilder.pipeline.breakdown.Scene
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class LinearScrollTest {
    private val scroll = LinearScroll(startY = 500.0, speed = 15.0)

    @Test
    fun `matches the spec formula 500 - t x 15`() {
        assertEquals(500.0, scroll.positionAt(0.0))
        assertEquals(485.0, scroll.positionAt(1.0))
        assertEquals(470.0, scroll.positionAt(2.0))
    }

    @Test
    fun `moves 15 over 24 pixels per frame at 24 fps`() {
        val frame1 = scroll.positionAt(1.0 / 24)
        assertEquals(499.375, frame1, 1e-9)
        assertEquals(15.0, scroll.positionAt(0.0) - scroll.positionAt(24.0 / 24), 1e-9)
    }
}

class TimelineBuilderTest {
    private val block = TextBlock(listOf("line"), lineHeight = 80, ascent = 60)

    private fun scenes(n: Int) = (0 until n).map { Scene(it, "scene $it", 2, 3.0) }

    @Test
    fun `scenes are contiguous and rounded up to whole frames`() {
        val timeline = TimelineBuilder().build(scenes(3), List(3) { block }, listOf(2.0, 3.5, 0.01), fps = 24)

        assertEquals(listOf(48, 84, 1), timeline.entries.map { it.frameCount })
        assertEquals(listOf(0, 48, 132), timeline.entries.map { it.startFrame })
        assertEquals(133, timeline.totalFrames)
        assertEquals(133.0 / 24, timeline.totalSeconds, 1e-9)
    }

    @Test
    fun `entryAt finds the scene on screen at each frame`() {
        val timeline = TimelineBuilder().build(scenes(3), List(3) { block }, listOf(2.0, 3.5, 0.01), fps = 24)

        assertEquals(0, timeline.entryAt(0).scene.index)
        assertEquals(0, timeline.entryAt(47).scene.index)
        assertEquals(1, timeline.entryAt(48).scene.index)
        assertEquals(1, timeline.entryAt(131).scene.index)
        assertEquals(2, timeline.entryAt(132).scene.index)
        assertThrows<IllegalArgumentException> { timeline.entryAt(133) }
    }
}
