package com.lucy.storybuilder.pipeline.assets

import com.lucy.storybuilder.pipeline.RenderSettings
import org.springframework.stereotype.Component
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.roundToInt

/** A scene's text wrapped to the canvas: the "canvas layer" the renderer draws every frame. */
data class TextBlock(
    val lines: List<String>,
    val lineHeight: Int,
    /** Distance from a line's top to its baseline. */
    val ascent: Int,
) {
    val height: Int get() = lines.size * lineHeight
}

fun RenderSettings.font(): Font = Font(fontName, Font.PLAIN, fontSize)

/** Measuring and drawing must share hints, or wrapped lines come out wider than they were measured. */
fun Graphics2D.applyTextHints() {
    setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
    setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
}

/** Stage 2 (visual layer): greedy word-wrap of scene text to the canvas width minus margins. */
@Component
class TextLayout {
    fun layout(
        text: String,
        settings: RenderSettings,
    ): TextBlock {
        val scratch = BufferedImage(1, 1, BufferedImage.TYPE_3BYTE_BGR)
        val g = scratch.createGraphics()
        try {
            g.applyTextHints()
            val metrics = g.getFontMetrics(settings.font())
            val maxWidth = settings.width - 2 * settings.margin

            val lines = mutableListOf<String>()
            var line = ""
            for (word in text.split(' ').filter { it.isNotEmpty() }) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && metrics.stringWidth(candidate) > maxWidth) {
                    lines += line
                    line = word
                } else {
                    line = candidate
                }
            }
            if (line.isNotEmpty()) lines += line

            return TextBlock(lines, (metrics.height * LINE_SPACING).roundToInt(), metrics.ascent)
        } finally {
            g.dispose()
        }
    }

    private companion object {
        const val LINE_SPACING = 1.15
    }
}
