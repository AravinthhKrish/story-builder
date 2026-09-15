package com.lucy.storybuilder.pipeline.timeline

/** A few words shown together on screen; [startWord] inclusive, [endWord] exclusive. */
data class CaptionChunk(
    val text: String,
    val startWord: Int,
    val endWord: Int,
)

/**
 * Shorts-style captions for one scene. TTS engines give no word timestamps, so each chunk is timed
 * by word position spread evenly over the scene's speech: a good match for steady narration.
 */
class SceneCaptions(
    val chunks: List<CaptionChunk>,
    /** How long the narration actually speaks; the last chunk stays up through the pause after it. */
    private val speechSeconds: Double,
) {
    private val totalWords = chunks.lastOrNull()?.endWord ?: 0

    fun at(sceneSeconds: Double): CaptionChunk? {
        if (chunks.isEmpty()) return null
        if (speechSeconds <= 0.0) return chunks.first()
        val word = (sceneSeconds / speechSeconds * totalWords).toInt()
        return chunks.firstOrNull { word < it.endWord } ?: chunks.last()
    }

    companion object {
        fun of(
            narration: String,
            maxWords: Int,
            speechSeconds: Double,
        ) = SceneCaptions(Captions.chunk(narration, maxWords), speechSeconds)
    }
}

object Captions {
    private val CLAUSE_END = Regex("[.,!?;:…]$")

    /** Up to [maxWords] per chunk, preferring to break after punctuation so chunks read as phrases. */
    fun chunk(
        text: String,
        maxWords: Int,
    ): List<CaptionChunk> {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val chunks = mutableListOf<CaptionChunk>()
        var start = 0
        for (i in words.indices) {
            val size = i - start + 1
            val atClauseEnd = CLAUSE_END.containsMatchIn(words[i]) && size >= 2
            if (size == maxWords || atClauseEnd || i == words.lastIndex) {
                chunks += CaptionChunk(words.subList(start, i + 1).joinToString(" "), start, i + 1)
                start = i + 1
            }
        }
        return chunks
    }
}
