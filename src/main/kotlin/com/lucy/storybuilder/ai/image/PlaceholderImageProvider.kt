package com.lucy.storybuilder.ai.image

import com.lucy.storybuilder.pipeline.assets.applyTextHints
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.random.Random

/**
 * Offline stand-in for an image model: a soft two-tone gradient with a few "bokeh" circles and the
 * scene's setting written on it. Deterministic per prompt + seed. Used when no image API key is
 * configured, in tests, and as the fallback when a real image request fails, so a video always renders.
 */
class PlaceholderImageProvider : ImageProvider {
    override val description = "placeholder"

    override fun generate(request: ImageRequest): BufferedImage {
        val random = Random(request.prompt.hashCode().toLong() xor request.seed)
        val image = BufferedImage(request.width, request.height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.applyTextHints()
            val top = Color.getHSBColor(random.nextFloat(), 0.45f, 0.55f)
            val bottom = Color.getHSBColor(random.nextFloat(), 0.55f, 0.25f)
            g.paint = GradientPaint(0f, 0f, top, 0f, request.height.toFloat(), bottom)
            g.fillRect(0, 0, request.width, request.height)

            repeat(7) {
                val r = request.width * (0.08 + random.nextDouble() * 0.18)
                g.color = Color(255, 255, 255, 18 + random.nextInt(30))
                g.fillOval(
                    (random.nextDouble() * request.width - r).toInt(),
                    (random.nextDouble() * request.height * 0.7 - r).toInt(),
                    (2 * r).toInt(),
                    (2 * r).toInt(),
                )
            }

            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.font = Font("SansSerif", Font.BOLD, request.width / 18)
            g.color = Color(255, 255, 255, 190)
            val words = request.label.split(' ').filter { it.isNotBlank() }
            val lines = words.chunked(3) { it.joinToString(" ") }.take(4)
            val lineHeight = g.fontMetrics.height
            val startY = (request.height * 0.38 - lines.size * lineHeight / 2.0).toInt()
            lines.forEachIndexed { i, line ->
                val x = (request.width - g.fontMetrics.stringWidth(line)) / 2
                g.drawString(line, x, startY + i * lineHeight)
            }
        } finally {
            g.dispose()
        }
        return image
    }
}
