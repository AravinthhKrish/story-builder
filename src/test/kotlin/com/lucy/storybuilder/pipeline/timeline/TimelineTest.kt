package com.lucy.storybuilder.pipeline.timeline

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    private val timeline = TimelineBuilder().build(listOf(2.0, 3.5, 0.01), fps = 24)

    @Test
    fun `scenes are contiguous and rounded up to whole frames`() {
        assertEquals(listOf(48, 84, 1), timeline.entries.map { it.frameCount })
        assertEquals(listOf(0, 48, 132), timeline.entries.map { it.startFrame })
        assertEquals(133, timeline.totalFrames)
        assertEquals(133.0 / 24, timeline.totalSeconds, 1e-9)
    }

    @Test
    fun `entryAt finds the scene on screen at each frame`() {
        assertEquals(0, timeline.entryAt(0).index)
        assertEquals(0, timeline.entryAt(47).index)
        assertEquals(1, timeline.entryAt(48).index)
        assertEquals(1.0, timeline.sceneTime(timeline.entryAt(72), 72), 1e-9)
        assertEquals(1, timeline.entryAt(131).index)
        assertEquals(2, timeline.entryAt(132).index)
        assertThrows<IllegalArgumentException> { timeline.entryAt(133) }
    }
}

class CameraMotionTest {
    @Test
    fun `zoom moves between 1 and 1_15 with eased ends`() {
        assertEquals(1.0, CameraMotion.at(CameraMove.ZOOM_IN, 0.0).zoom, 1e-9)
        assertEquals(1.15, CameraMotion.at(CameraMove.ZOOM_IN, 1.0).zoom, 1e-9)
        assertEquals(1.075, CameraMotion.at(CameraMove.ZOOM_IN, 0.5).zoom, 1e-9)
        assertEquals(1.15, CameraMotion.at(CameraMove.ZOOM_OUT, 0.0).zoom, 1e-9)
        assertEquals(1.0, CameraMotion.at(CameraMove.ZOOM_OUT, 1.0).zoom, 1e-9)
        // Eased: the first 10% of time covers far less than 10% of the move.
        assertTrue(CameraMotion.at(CameraMove.ZOOM_IN, 0.1).zoom - 1.0 < 0.15 * 0.05)
    }

    @Test
    fun `pans sweep the camera across the spare image area`() {
        val left = listOf(0.0, 0.5, 1.0).map { CameraMotion.at(CameraMove.PAN_LEFT, it) }
        assertEquals(listOf(1.0, 0.0, -1.0), left.map { it.panX })
        assertTrue(left.all { it.zoom > 1.0 && it.panY == 0.0 }, "pans need zoom headroom")
        assertEquals(-1.0, CameraMotion.at(CameraMove.PAN_RIGHT, 0.0).panX)
        assertEquals(-1.0, CameraMotion.at(CameraMove.PAN_UP, 1.0).panY)
        assertEquals(1.0, CameraMotion.at(CameraMove.PAN_UP, 5.0).panY + 2.0, 1e-9, "progress is clamped")
    }

    @Test
    fun `camera moves parse leniently and serialise in snake case`() {
        assertEquals(CameraMove.PAN_RIGHT, CameraMove.parse("pan-right"))
        assertEquals(CameraMove.ZOOM_IN, CameraMove.parse(" Zoom In "))
        assertEquals(null, CameraMove.parseOrNull("sideways"))
        assertEquals("pan_up", CameraMove.PAN_UP.json())
    }
}

class CaptionsTest {
    @Test
    fun `chunks hold at most max words and prefer breaking after punctuation`() {
        val chunks = Captions.chunk("Once upon a time, a small lighthouse stood alone at the edge of the sea.", 5)
        assertEquals(listOf("Once upon a time,", "a small lighthouse stood alone", "at the edge of the", "sea."), chunks.map { it.text })
        assertEquals(0, chunks.first().startWord)
        assertEquals(15, chunks.last().endWord)
    }

    @Test
    fun `chunks are timed by word position across the speech`() {
        val captions = SceneCaptions.of("one two three four five six seven eight", maxWords = 4, speechSeconds = 4.0)
        assertEquals("one two three four", captions.at(0.0)?.text)
        assertEquals("one two three four", captions.at(1.9)?.text)
        assertEquals("five six seven eight", captions.at(2.1)?.text)
        assertEquals("five six seven eight", captions.at(9.0)?.text, "last chunk holds through the pause")
        assertEquals(null, SceneCaptions.of("", 4, 1.0).at(0.0))
    }
}

class DurationBudgetTest {
    @Test
    fun `short stories are untouched`() {
        val fit = DurationBudget.fit(listOf(5.0, 6.0), listOf(3.0, 3.0), 0.5, 60.0, 1.25)
        assertEquals(1.0, fit.speed)
        assertEquals(listOf(5.5, 6.5), fit.slots)
        assertTrue(fit.notes.isEmpty())
    }

    @Test
    fun `minimum slot wins over short narration`() {
        val fit = DurationBudget.fit(listOf(1.0), listOf(3.0), 0.5, 60.0, 1.25)
        assertEquals(listOf(3.0), fit.slots)
    }

    @Test
    fun `slightly long narration is sped up just enough`() {
        val fit = DurationBudget.fit(List(6) { 10.5 }, List(6) { 3.0 }, 0.5, 60.0, 1.25) // 66 s natural
        assertTrue(fit.speed in 1.1..1.25, "speed ${fit.speed}")
        assertEquals(6, fit.sceneCount)
        assertTrue(fit.slots.sum() <= 59.5 + 1e-6, "total ${fit.slots.sum()}")
        assertTrue(fit.slots.sum() > 59.0, "uses the budget rather than over-speeding")
        assertEquals(1, fit.notes.size)
    }

    @Test
    fun `far too long narration drops trailing scenes at max speed`() {
        val fit = DurationBudget.fit(List(10) { 12.0 }, List(10) { 3.0 }, 0.5, 60.0, 1.25) // 125 s natural
        assertEquals(1.25, fit.speed)
        assertEquals(5, fit.sceneCount) // 10.1 s each at 1.25x -> 5 fit in 59.5 s
        assertTrue(fit.slots.sum() <= 59.5)
        assertTrue(fit.notes.single().contains("dropped"))
    }
}
