package com.lucy.storybuilder.pipeline.render

import com.lucy.storybuilder.pipeline.RenderSettings
import com.lucy.storybuilder.pipeline.assets.applyTextHints
import com.lucy.storybuilder.pipeline.assets.font
import com.lucy.storybuilder.pipeline.timeline.MotionFunction
import com.lucy.storybuilder.pipeline.timeline.Timeline
import java.awt.Color
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import kotlin.math.roundToInt

/**
 * Stage 4 (frame assembly): draws one frame into a reused BGR buffer whose bytes are exactly
 * ffmpeg's `rawvideo bgr24` input. Not thread-safe — one renderer per job.
 */
class FrameRenderer(
    private val settings: RenderSettings,
    private val motion: MotionFunction,
) : AutoCloseable {
    private val image = BufferedImage(settings.width, settings.height, BufferedImage.TYPE_3BYTE_BGR)
    private val pixels: ByteArray = (image.raster.dataBuffer as DataBufferByte).data
    private val g =
        image.createGraphics().apply {
            applyTextHints()
            font = settings.font()
        }

    /** Returns the shared pixel buffer; it is overwritten by the next call. */
    fun render(
        timeline: Timeline,
        frame: Int,
    ): ByteArray {
        val entry = timeline.entryAt(frame)
        val t = (frame - entry.startFrame).toDouble() / timeline.fps
        val block = entry.block

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
        return pixels
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
