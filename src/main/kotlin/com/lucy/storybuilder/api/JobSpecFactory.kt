package com.lucy.storybuilder.api

import com.lucy.storybuilder.api.dto.CreateStoryRequest
import com.lucy.storybuilder.api.dto.StoryOptions
import com.lucy.storybuilder.config.StoryBuilderProperties
import com.lucy.storybuilder.pipeline.JobSpec
import com.lucy.storybuilder.pipeline.RenderSettings
import org.springframework.stereotype.Component
import java.awt.Color
import kotlin.math.roundToInt

/** Merges request options over configured defaults and applies the checks bean validation can't express. */
@Component
class JobSpecFactory(
    private val props: StoryBuilderProperties,
) {
    fun create(request: CreateStoryRequest): JobSpec {
        val text = request.text.orEmpty()
        if (text.length > props.maxTextLength) {
            throw InvalidStoryException("text is ${text.length} characters; the limit is ${props.maxTextLength}")
        }
        val options = request.options ?: StoryOptions()
        return JobSpec(
            text = text,
            wordsPerMinute = options.wordsPerMinute ?: props.breakdown.wordsPerMinute,
            voice = options.voice,
            render = renderSettings(options),
        )
    }

    private fun renderSettings(options: StoryOptions): RenderSettings {
        val defaults = props.render
        val width = options.width ?: defaults.width
        val height = options.height ?: defaults.height
        // libx264 with yuv420p subsamples chroma 2x2, so odd dimensions are rejected by the encoder.
        if (width % 2 != 0 || height % 2 != 0) throw InvalidStoryException("width and height must be even, got ${width}x$height")

        // Defaults are tuned for 1920x1080; scale the ones measured in pixels to other resolutions.
        val sx = width.toDouble() / defaults.width
        val sy = height.toDouble() / defaults.height
        return RenderSettings(
            width = width,
            height = height,
            fps = options.fps ?: defaults.fps,
            startY = options.startY ?: (defaults.startY * sy),
            scrollSpeed = options.scrollSpeed ?: (defaults.scrollSpeed * sy),
            backgroundColor = Color.decode(options.backgroundColor ?: defaults.backgroundColor),
            textColor = Color.decode(options.textColor ?: defaults.textColor),
            fontName = defaults.fontName,
            fontSize = options.fontSize ?: (defaults.fontSize * sy).roundToInt().coerceAtLeast(8),
            margin = (defaults.margin * sx).roundToInt(),
            fadeSeconds = defaults.fadeSeconds,
        )
    }
}
