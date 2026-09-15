package com.lucy.storybuilder.pipeline.breakdown

import com.lucy.storybuilder.config.StoryBuilderProperties
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StoryBreakdownServiceTest {
    private fun service(
        maxWords: Int = 40,
        minSeconds: Double = 3.0,
    ) = StoryBreakdownService(
        StoryBuilderProperties(breakdown = StoryBuilderProperties.Breakdown(maxWordsPerScene = maxWords, minSceneSeconds = minSeconds)),
    )

    private fun words(n: Int) = (1..n).joinToString(" ") { "w$it" }

    @Test
    fun `each paragraph becomes a scene`() {
        val scenes = service().breakdown("First paragraph.\n\nSecond one,\nwrapped by the editor.\n \n\n  Third.  ")

        assertEquals(listOf("First paragraph.", "Second one, wrapped by the editor.", "Third."), scenes.map { it.text })
        assertEquals(listOf(0, 1, 2), scenes.map { it.index })
        assertEquals(listOf(2, 6, 1), scenes.map { it.wordCount })
    }

    @Test
    fun `windows line endings split paragraphs too`() {
        assertEquals(2, service().breakdown("One.\r\n\r\nTwo.").size)
    }

    @Test
    fun `duration follows reading speed with a floor`() {
        val scenes = service(maxWords = 100).breakdown("${words(30)}\n\nShort.", wordsPerMinute = 150)

        assertEquals(12.0, scenes[0].estimatedSeconds, 1e-9) // 30 words / 150 wpm = 0.2 min
        assertEquals(3.0, scenes[1].estimatedSeconds, 1e-9) // min-scene floor
    }

    @Test
    fun `long paragraph is packed into scenes on sentence boundaries`() {
        val sentences = (1..5).map { s -> (1..10).joinToString(" ") { "s${s}w$it" } + "." }
        val paragraph = sentences.joinToString(" ")

        val scenes = service(maxWords = 40).breakdown(paragraph)

        assertEquals(listOf(40, 10), scenes.map { it.wordCount })
        assertEquals(sentences.take(4).joinToString(" "), scenes[0].text)
        assertEquals(paragraph, scenes.joinToString(" ") { it.text })
    }

    @Test
    fun `run-on sentence is cut on word boundaries`() {
        val scenes = service(maxWords = 40).breakdown(words(100))

        assertEquals(listOf(40, 40, 20), scenes.map { it.wordCount })
        assertTrue(scenes.all { it.wordCount <= 40 })
    }

    @Test
    fun `blank story has no scenes`() {
        assertTrue(service().breakdown(" \n\n \t ").isEmpty())
    }
}
