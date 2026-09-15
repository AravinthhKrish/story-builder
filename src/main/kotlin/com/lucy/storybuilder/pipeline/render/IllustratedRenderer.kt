package com.lucy.storybuilder.pipeline.render

import com.lucy.storybuilder.pipeline.VideoFormat
import com.lucy.storybuilder.pipeline.assets.applyTextHints
import com.lucy.storybuilder.pipeline.timeline.CameraMotion
import com.lucy.storybuilder.pipeline.timeline.CameraMove
import com.lucy.storybuilder.pipeline.timeline.CaptionChunk
import com.lucy.storybuilder.pipeline.timeline.SceneCaptions
import com.lucy.storybuilder.pipeline.timeline.Timeline
import com.lucy.storybuilder.skills.CaptionStyle
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.TextLayout
import java.awt.geom.AffineTransform
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import kotlin.math.max
import kotlin.math.roundToInt

data class IllustratedScene(
    val image: BufferedImage,
    val camera: CameraMove,
    val captions: SceneCaptions,
)

/**
 * The illustrated (Shorts) look: each scene's picture moves with its camera formula, scenes
 * crossfade, the video fades in from and out to black, and captions sit in a soft box in the lower
 * third. Pictures are pre-scaled once ([prepare]) to just above the largest zoom, so each frame is
 * only a small bilinear transform. One instance per thread; prepared pictures are shared read-only.
 */
class IllustratedRenderer(
    format: VideoFormat,
    private val caption: CaptionStyle,
    private val transitionSeconds: Double,
    private val timeline: Timeline,
    /** Output of [prepare]. */
    private val prepared: List<IllustratedScene>,
) : SceneRenderer {
    private val width = format.width
    private val height = format.height
    private val canvas = BgrCanvas(width, height)
    private val g =
        canvas.image.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED)
        }
    private val captionCache = HashMap<CaptionChunk, BufferedImage>()

    override fun render(frame: Int): ByteArray {
        val entry = timeline.entryAt(frame)
        val scene = prepared[entry.index]
        val t = timeline.sceneTime(entry, frame)
        val duration = timeline.seconds(entry)
        val progress = (t / duration).coerceIn(0.0, 1.0)

        g.composite = AlphaComposite.SrcOver
        g.color = Color.BLACK
        g.fillRect(0, 0, width, height)

        val fadeIn = if (transitionSeconds > 0) (t / transitionSeconds).coerceIn(0.0, 1.0) else 1.0
        val isLast = entry.index == prepared.lastIndex
        val fadeOut = if (isLast && transitionSeconds > 0) ((duration - t) / transitionSeconds).coerceIn(0.0, 1.0) else 1.0

        // Crossfade: the previous picture holds its final camera position underneath. The first scene
        // fades in over black instead, and the last one fades out to black.
        if (entry.index > 0 && fadeIn < 1.0) drawPicture(prepared[entry.index - 1], 1.0, 1f)
        drawPicture(scene, progress, (fadeIn * fadeOut).toFloat())

        scene.captions.at(t)?.let { chunk ->
            g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, fadeOut.toFloat())
            val img = captionCache.getOrPut(chunk) { renderCaption(chunk.text) }
            val y = (height * caption.positionY - img.height / 2.0).roundToInt()
            g.drawImage(img, (width - img.width) / 2, y, null)
        }
        return canvas.pixels
    }

    private fun drawPicture(
        scene: IllustratedScene,
        progress: Double,
        alpha: Float,
    ) {
        if (alpha <= 0f) return
        val img = scene.image
        val cam = CameraMotion.at(scene.camera, progress)
        // Pictures were pre-scaled to cover the frame at MAX_ZOOM, so zoom 1.15 means scale 1.0.
        val scale = cover(width, height, img.width, img.height) * cam.zoom
        val drawnW = img.width * scale
        val drawnH = img.height * scale
        val cx = width / 2.0 - cam.panX * (drawnW - width) / 2.0
        val cy = height / 2.0 - cam.panY * (drawnH - height) / 2.0
        val at = AffineTransform(scale, 0.0, 0.0, scale, cx - drawnW / 2.0, cy - drawnH / 2.0)
        g.composite = if (alpha >= 1f) AlphaComposite.SrcOver else AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
        g.drawImage(img, at, null)
    }

    companion object {
        private fun cover(
            frameW: Int,
            frameH: Int,
            w: Int,
            h: Int,
        ): Double = max(frameW.toDouble() / w, frameH.toDouble() / h)

        /** Resizes each picture once (high quality) to cover the frame at the largest zoom, in the canvas's pixel format. */
        fun prepare(
            scenes: List<IllustratedScene>,
            format: VideoFormat,
        ): List<IllustratedScene> =
            scenes.map { scene ->
                val source = scene.image
                val scale = cover(format.width, format.height, source.width, source.height) * CameraMotion.MAX_ZOOM
                val w = (source.width * scale).roundToInt()
                val h = (source.height * scale).roundToInt()
                val out = BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR)
                val sg = out.createGraphics()
                try {
                    sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                    sg.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                    sg.drawImage(source, 0, 0, w, h, null)
                } finally {
                    sg.dispose()
                }
                scene.copy(image = out)
            }
    }

    /** Caption chunk as an ARGB sprite: rounded translucent box, outlined bold text, up to two lines. */
    private fun renderCaption(text: String): BufferedImage {
        val font = Font(caption.fontName, Font.BOLD, caption.fontSize)
        val maxWidth = width * 0.86
        val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()
        val frc = probe.apply { applyTextHints() }.fontRenderContext
        val metrics = probe.getFontMetrics(font)
        val lines = wrap(text, maxWidth) { metrics.stringWidth(it).toDouble() }
        probe.dispose()

        val padX = caption.fontSize * 0.6
        val padY = caption.fontSize * 0.35
        val lineHeight = metrics.height
        val textWidth = lines.maxOf { metrics.stringWidth(it) }
        val w = (textWidth + 2 * padX).roundToInt()
        val h = (lines.size * lineHeight + 2 * padY).roundToInt()
        val sprite = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val cg: Graphics2D = sprite.createGraphics()
        try {
            cg.applyTextHints()
            val box = Color.decode(caption.boxColor)
            cg.color = Color(box.red, box.green, box.blue, (caption.boxOpacity.coerceIn(0.0, 1.0) * 255).roundToInt())
            cg.fill(RoundRectangle2D.Double(0.0, 0.0, w.toDouble(), h.toDouble(), h * 0.45, h * 0.45))

            val outline = Color.decode(caption.outlineColor)
            val fill = Color.decode(caption.textColor)
            cg.stroke = BasicStroke(caption.fontSize / 7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            lines.forEachIndexed { i, line ->
                val layout = TextLayout(line, font, frc)
                val x = (w - layout.advance) / 2.0
                val baseline = padY + i * lineHeight + metrics.ascent
                val shape = layout.getOutline(AffineTransform.getTranslateInstance(x, baseline))
                cg.color = outline
                cg.draw(shape)
                cg.color = fill
                cg.fill(shape)
            }
        } finally {
            cg.dispose()
        }
        return sprite
    }

    private fun wrap(
        text: String,
        maxWidth: Double,
        width: (String) -> Double,
    ): List<String> {
        val lines = mutableListOf<String>()
        var line = ""
        for (word in text.split(' ').filter { it.isNotBlank() }) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (line.isNotEmpty() && width(candidate) > maxWidth) {
                lines += line
                line = word
            } else {
                line = candidate
            }
        }
        if (line.isNotEmpty()) lines += line
        return lines.ifEmpty { listOf("") }
    }

    override fun close() = g.dispose()
}
