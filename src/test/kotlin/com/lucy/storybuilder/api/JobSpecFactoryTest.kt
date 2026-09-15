package com.lucy.storybuilder.api

import com.lucy.storybuilder.NoopTts
import com.lucy.storybuilder.api.dto.CreateStoryRequest
import com.lucy.storybuilder.api.dto.StoryOptions
import com.lucy.storybuilder.bundledSkills
import com.lucy.storybuilder.config.StoryBuilderProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.awt.Color
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JobSpecFactoryTest {
    private val props = StoryBuilderProperties(maxTextLength = 100)
    private val factory = JobSpecFactory(props, bundledSkills(props), NoopTts("piper"))

    @Test
    fun `default skill is the vertical cartoon shorts format`() {
        val spec = factory.create(CreateStoryRequest(text = "Hello."))

        assertEquals("cartoon-storybook", spec.skill.name)
        assertTrue(spec.skill.isIllustrated)
        assertEquals(720 to 1280, spec.format.width to spec.format.height)
        assertEquals(30, spec.format.fps)
        assertEquals("veryfast", spec.format.preset)
        assertEquals("en_US-lessac-medium", spec.voice, "skill voice for the active TTS engine")
    }

    @Test
    fun `text-scroll keeps the original spec grid and motion`() {
        val spec = factory.create(CreateStoryRequest(text = "Hello.", skill = "text-scroll"))

        with(spec.textScroll) {
            assertEquals(1920 to 1080, width to height)
            assertEquals(24, fps)
            assertEquals(500.0, startY)
            assertEquals(15.0, scrollSpeed)
        }
        assertEquals(1920 to 1080, spec.format.width to spec.format.height)
        assertEquals(150, spec.wordsPerMinute)
    }

    @Test
    fun `text-scroll options win and pixel defaults scale with resolution`() {
        val spec =
            factory.create(
                CreateStoryRequest(
                    "Hello.",
                    "text-scroll",
                    StoryOptions(width = 1280, height = 720, textColor = "#FF0000", wordsPerMinute = 180),
                ),
            )

        with(spec.textScroll) {
            assertEquals(333.33, startY, 0.01)
            assertEquals(10.0, scrollSpeed, 1e-9)
            assertEquals(43, fontSize)
            assertEquals(Color.RED, textColor)
        }
        assertEquals(180, spec.wordsPerMinute)
    }

    @Test
    fun `illustrated format can be overridden per request`() {
        val spec = factory.create(CreateStoryRequest("Hi.", "anime", StoryOptions(width = 1080, height = 1920, fps = 24, voice = "x")))
        assertEquals(Triple(1080, 1920, 24), Triple(spec.format.width, spec.format.height, spec.format.fps))
        assertEquals("x", spec.voice)
    }

    @Test
    fun `unknown skill, odd dimensions and oversized text are rejected`() {
        assertThrows<InvalidStoryException> { factory.create(CreateStoryRequest("Hi.", "no-such-skill")) }
        assertThrows<InvalidStoryException> { factory.create(CreateStoryRequest("Hi.", "text-scroll", StoryOptions(width = 1921))) }
        assertThrows<InvalidStoryException> { factory.create(CreateStoryRequest("Hi.", "anime", StoryOptions(height = 1279))) }
        assertThrows<InvalidStoryException> { factory.create(CreateStoryRequest("x".repeat(101))) }
    }
}
