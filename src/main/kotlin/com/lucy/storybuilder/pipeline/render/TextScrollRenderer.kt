package com.lucy.storybuilder.pipeline.render

import com.lucy.storybuilder.pipeline.RenderSettings
import com.lucy.storybuilder.pipeline.assets.TextBlock
import com.lucy.storybuilder.pipeline.assets.applyTextHints
import com.lucy.storybuilder.pipeline.assets.font
import com.lucy.storybuilder.pipeline.timeline.MotionFunction
import com.lucy.storybuilder.pipeline.timeline.Timeline
import java.awt.Color
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import kotlin.math.roundToInt

/** Stage 4 (frame assembly): draws one frame for [frame] and returns ffmpeg `rawvideo bgr24` bytes. */
interface SceneRenderer : AutoCloseable {
    /** Returns a shared pixel buffer that the next call overwrites. Not thread-safe — one renderer per job. */
    fun render(frame: Int): ByteArray
}

/** A reusable BGR frame buffer whose backing bytes are exactly ffmpeg's `bgr24` layout. */
internal class BgrCanvas(
    width: Int,
    height: Int,
) {
    val image = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
    val pixels: ByteArray = (image.raster.dataBuffer as DataBufferByte).data
}

/** The text-scroll skill: scene text on a plain background, moved by the spec's `500 - t * 15`. */
class TextScrollRenderer(
    private val settings: RenderSettings,
    private val motion: MotionFunction,
    private val timeline: Timeline,
    private val blocks: List<TextBlock>,
) : SceneRenderer {
    private val canvas = BgrCanvas(settings.width, settings.height)
    private val g =
        canvas.image.createGraphics().apply {
            applyTextHints()
            font = settings.font()
        }

    override fun render(frame: Int): ByteArray {
        val entry = timeline.entryAt(frame)
        val t = timeline.sceneTime(entry, frame)
        val block = blocks[entry.index]

        g.color = settings.backgroundColor
        g.fillRect(0, 0, settings.width, settings.height)

        g.color = blend(settings.backgroundColor, settings.textColor, fadeAlpha(t, timeline.seconds(entry)))
        val top = motion.positionAt(t) - block.height / 2.0
        val metrics = g.fontMetrics
        block.lines.forEachIndexed { i, line ->
            val x = (settings.width - metrics.stringWidth(line)) / 2f
            val baseline = (top + i * block.lineHeight + block.ascent).toFloat()
            g.drawString(line, x, baseline)
        }
        return canvas.pixels
    }

    private fun fadeAlpha(
        t: Double,
        sceneSeconds: Double,
    ): Double {
        val fade = settings.fadeSeconds
        if (fade <= 0.0) return 1.0
        return minOf(1.0, t / fade, (sceneSeconds - t) / fade).coerceIn(0.0, 1.0)
    }

    /** Fading by colour mix rather than alpha compositing: the background is solid, and it's cheaper. */
    private fun blend(
        from: Color,
        to: Color,
        alpha: Double,
    ): Color {
        fun mix(
            a: Int,
            b: Int,
        ) = (a + (b - a) * alpha).roundToInt()
        return Color(mix(from.red, to.red), mix(from.green, to.green), mix(from.blue, to.blue))
    }

    override fun close() = g.dispose()
}
