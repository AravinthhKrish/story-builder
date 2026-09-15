package com.lucy.storybuilder.api

import com.lucy.storybuilder.api.dto.CreateStoryRequest
import com.lucy.storybuilder.api.dto.StoryOptions
import com.lucy.storybuilder.config.StoryBuilderProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.awt.Color
import kotlin.test.assertEquals

class JobSpecFactoryTest {
    private val factory = JobSpecFactory(StoryBuilderProperties(maxTextLength = 100))

    @Test
    fun `defaults match the spec grid and motion`() {
        val spec = factory.create(CreateStoryRequest(text = "Hello."))

        with(spec.render) {
            assertEquals(1920 to 1080, width to height)
            assertEquals(24, fps)
            assertEquals(500.0, startY)
            assertEquals(15.0, scrollSpeed)
        }
        assertEquals(150, spec.wordsPerMinute)
    }

    @Test
    fun `explicit options win and pixel defaults scale with resolution`() {
        val spec =
            factory.create(
                CreateStoryRequest("Hello.", StoryOptions(width = 1280, height = 720, textColor = "#FF0000", wordsPerMinute = 180)),
            )

        with(spec.render) {
            assertEquals(333.33, startY, 0.01)
            assertEquals(10.0, scrollSpeed, 1e-9)
            assertEquals(43, fontSize)
            assertEquals(Color.RED, textColor)
        }
        assertEquals(180, spec.wordsPerMinute)
    }

    @Test
    fun `odd dimensions are rejected`() {
        assertThrows<InvalidStoryException> { factory.create(CreateStoryRequest("Hi.", StoryOptions(width = 1921))) }
    }

    @Test
    fun `text over the configured limit is rejected`() {
        assertThrows<InvalidStoryException> { factory.create(CreateStoryRequest("x".repeat(101))) }
    }
}
