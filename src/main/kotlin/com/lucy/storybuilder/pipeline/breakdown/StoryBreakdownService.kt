package com.lucy.storybuilder.pipeline.breakdown

import com.lucy.storybuilder.config.StoryBuilderProperties
import org.springframework.stereotype.Service
import java.text.BreakIterator
import java.util.Locale

data class Scene(
    val index: Int,
    val text: String,
    val wordCount: Int,
    /** Reading-speed estimate; the real duration is settled once narration audio exists. */
    val estimatedSeconds: Double,
)

/**
 * Stage 1 — Story Breakdown.
 *
 * Paragraphs (blank-line separated) become scenes. A paragraph longer than `maxWordsPerScene` is
 * split on sentence boundaries and the sentences packed greedily, so no scene overflows the screen.
 */
@Service
class StoryBreakdownService(
    props: StoryBuilderProperties,
) {
    private val config = props.breakdown

    fun breakdown(
        text: String,
        wordsPerMinute: Int = config.wordsPerMinute,
    ): List<Scene> =
        text
            .replace("\r\n", "\n")
            .split(PARAGRAPH_BREAK)
            .map { it.replace(WHITESPACE, " ").trim() }
            .filter { it.isNotEmpty() }
            .flatMap { chunk(it) }
            .mapIndexed { index, sceneText ->
                val words = wordCount(sceneText)
                Scene(
                    index = index,
                    text = sceneText,
                    wordCount = words,
                    estimatedSeconds = maxOf(config.minSceneSeconds, words * 60.0 / wordsPerMinute),
                )
            }

    private fun chunk(paragraph: String): List<String> {
        if (wordCount(paragraph) <= config.maxWordsPerScene) return listOf(paragraph)

        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        var currentWords = 0
        for (piece in sentences(paragraph).flatMap { splitOversized(it) }) {
            val words = wordCount(piece)
            if (currentWords > 0 && currentWords + words > config.maxWordsPerScene) {
                chunks += current.toString()
                current.clear()
                currentWords = 0
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(piece)
            currentWords += words
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }

    private fun sentences(paragraph: String): List<String> {
        val iterator = BreakIterator.getSentenceInstance(Locale.ENGLISH).apply { setText(paragraph) }
        val result = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            paragraph
                .substring(start, end)
                .trim()
                .takeIf { it.isNotEmpty() }
                ?.let(result::add)
            start = end
            end = iterator.next()
        }
        return result
    }

    /** A single run-on sentence longer than a scene is cut on word boundaries. */
    private fun splitOversized(sentence: String): List<String> =
        if (wordCount(sentence) <= config.maxWordsPerScene) {
            listOf(sentence)
        } else {
            sentence.split(' ').chunked(config.maxWordsPerScene) { it.joinToString(" ") }
        }

    private fun wordCount(text: String): Int = text.split(' ').count { it.isNotBlank() }

    private companion object {
        val PARAGRAPH_BREAK = Regex("\\n\\s*\\n")
        val WHITESPACE = Regex("\\s+")
    }
}
