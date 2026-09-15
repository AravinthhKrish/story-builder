package com.lucy.storybuilder.api

import com.lucy.storybuilder.api.dto.CreateStoryRequest
import com.lucy.storybuilder.api.dto.StoryOptions
import com.lucy.storybuilder.config.StoryBuilderProperties
import com.lucy.storybuilder.pipeline.JobSpec
import com.lucy.storybuilder.pipeline.RenderSettings
import com.lucy.storybuilder.pipeline.VideoFormat
import com.lucy.storybuilder.pipeline.assets.tts.TtsEngine
import com.lucy.storybuilder.script.StoryScript
import com.lucy.storybuilder.skills.Skill
import com.lucy.storybuilder.skills.SkillRegistry
import com.lucy.storybuilder.skills.UnknownSkillException
import org.springframework.stereotype.Component
import java.awt.Color
import kotlin.math.roundToInt

/**
 * Merges request options over the skill and configured defaults, and applies the checks bean
 * validation can't express. Precedence: request option → skill → `storybuilder.*`.
 */
@Component
class JobSpecFactory(
    private val props: StoryBuilderProperties,
    private val skills: SkillRegistry,
    private val tts: TtsEngine,
) {
    /** One-shot: the director runs inside the job. */
    fun create(request: CreateStoryRequest): JobSpec = build(skill(request.skill), checkText(request.text), null, request.options)

    /** Staged: render a script that already exists. */
    fun forScript(
        script: StoryScript,
        options: StoryOptions?,
    ): JobSpec = build(skill(script.skill), null, script, options)

    fun skill(name: String?): Skill =
        try {
            skills.get(name ?: props.defaultSkill)
        } catch (e: UnknownSkillException) {
            throw InvalidStoryException(e.message ?: "Unknown skill")
        }

    fun checkText(text: String?): String {
        val value = text.orEmpty()
        if (value.length > props.maxTextLength) {
            throw InvalidStoryException("text is ${value.length} characters; the limit is ${props.maxTextLength}")
        }
        return value
    }

    private fun build(
        skill: Skill,
        text: String?,
        script: StoryScript?,
        options: StoryOptions?,
    ): JobSpec {
        val opts = options ?: StoryOptions()
        val textScroll = textScrollSettings(opts, skill)
        val format =
            if (skill.isIllustrated) {
                VideoFormat(
                    width = opts.width ?: skill.format.width,
                    height = opts.height ?: skill.format.height,
                    fps = opts.fps ?: skill.format.fps,
                    crf = skill.format.crf,
                    preset = skill.format.preset,
                ).also { checkEven(it.width, it.height) }
            } else {
                VideoFormat(textScroll.width, textScroll.height, textScroll.fps, skill.format.crf, skill.format.preset)
            }
        return JobSpec(
            skill = skill,
            text = text,
            script = script,
            wordsPerMinute = opts.wordsPerMinute ?: skill.wordsPerMinute ?: props.breakdown.wordsPerMinute,
            voice = opts.voice ?: skill.voices[tts.name],
            format = format,
            textScroll = textScroll,
        )
    }

    private fun textScrollSettings(
        options: StoryOptions,
        skill: Skill,
    ): RenderSettings {
        val defaults = props.render
        val useOptions = !skill.isIllustrated
        val width = options.width?.takeIf { useOptions } ?: defaults.width
        val height = options.height?.takeIf { useOptions } ?: defaults.height
        checkEven(width, height)

        // Defaults are tuned for 1920x1080; scale the ones measured in pixels to other resolutions.
        val sx = width.toDouble() / defaults.width
        val sy = height.toDouble() / defaults.height
        return RenderSettings(
            width = width,
            height = height,
            fps = options.fps?.takeIf { useOptions } ?: defaults.fps,
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

    // libx264 with yuv420p subsamples chroma 2x2, so odd dimensions are rejected by the encoder.
    private fun checkEven(
        width: Int,
        height: Int,
    ) {
        if (width % 2 != 0 || height % 2 != 0) throw InvalidStoryException("width and height must be even, got ${width}x$height")
    }
}
