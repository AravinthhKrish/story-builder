package com.lucy.storybuilder.pipeline.render

import com.lucy.storybuilder.pipeline.RenderSettings
import com.lucy.storybuilder.pipeline.assets.TextLayout
import com.lucy.storybuilder.pipeline.breakdown.Scene
import com.lucy.storybuilder.pipeline.timeline.LinearScroll
import com.lucy.storybuilder.pipeline.timeline.Timeline
import com.lucy.storybuilder.pipeline.timeline.TimelineBuilder
import org.junit.jupiter.api.Test
import java.awt.Color
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameRendererTest {
    private val background = Color(0x10, 0x20, 0x30)

    private fun settings(fade: Double = 0.0) =
        RenderSettings(
            width = 320,
            height = 180,
            fps = 10,
            startY = 90.0,
            scrollSpeed = 20.0,
            backgroundColor = background,
            textColor = Color.WHITE,
            fontName = "SansSerif",
            fontSize = 24,
            margin = 20,
            fadeSeconds = fade,
        )

    private fun timeline(settings: RenderSettings): Timeline {
        val scene = Scene(0, "HELLO WORLD", 2, 3.0)
        val block = TextLayout().layout(scene.text, settings)
        return TimelineBuilder().build(listOf(scene), listOf(block), listOf(3.0), settings.fps)
    }

    /** Rows that contain at least one pixel differing from the background. */
    private fun inkedRows(
        pixels: ByteArray,
        s: RenderSettings,
    ): List<Int> =
        (0 until s.height).filter { y ->
            (0 until s.width).any { x ->
                val i = (y * s.width + x) * 3 // TYPE_3BYTE_BGR: B, G, R
                pixels[i].toInt() and 0xFF != background.blue ||
                    pixels[i + 1].toInt() and 0xFF != background.green ||
                    pixels[i + 2].toInt() and 0xFF != background.red
            }
        }

    @Test
    fun `buffer is bgr24 with background and text centred on the motion position`() {
        val s = settings()
        val pixels = FrameRenderer(s, LinearScroll(s.startY, s.scrollSpeed)).use { it.render(timeline(s), 0).copyOf() }

        assertEquals(s.width * s.height * 3, pixels.size)
        assertEquals(listOf(background.blue, background.green, background.red), (0..2).map { pixels[it].toInt() and 0xFF })

        val rows = inkedRows(pixels, s)
        assertTrue(rows.isNotEmpty(), "text should be drawn")
        val centre = (rows.first() + rows.last()) / 2.0
        assertEquals(90.0, centre, 12.0, "text block should be centred near startY")
    }

    @Test
    fun `text scrolls up by speed x time`() {
        val s = settings()
        val tl = timeline(s)
        FrameRenderer(s, LinearScroll(s.startY, s.scrollSpeed)).use { renderer ->
            val top0 = inkedRows(renderer.render(tl, 0), s).first()
            val top20 = inkedRows(renderer.render(tl, 20), s).first() // t = 2 s -> 40 px higher
            assertEquals(40, top0 - top20, "moved ${top0 - top20}px")
        }
    }

    @Test
    fun `first frame of a scene is fully faded out`() {
        val s = settings(fade = 0.5)
        val pixels = FrameRenderer(s, LinearScroll(s.startY, s.scrollSpeed)).use { it.render(timeline(s), 0) }
        assertTrue(inkedRows(pixels, s).isEmpty())
    }
}
