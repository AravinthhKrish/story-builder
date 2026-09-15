package com.lucy.storybuilder.pipeline

import com.lucy.storybuilder.script.StoryScript
import com.lucy.storybuilder.skills.Skill
import java.awt.Color

/**
 * Everything one render needs: request options already merged over skill and config defaults.
 * Exactly one of [text] (one-shot: the director runs inside the job) or [script] (staged) is set.
 */
data class JobSpec(
    val skill: Skill,
    val text: String? = null,
    val script: StoryScript? = null,
    val wordsPerMinute: Int,
    /** Null means the TTS engine's own default voice. */
    val voice: String?,
    val format: VideoFormat,
    /** Look of the text-scroll renderer; unused by illustrated skills. */
    val textScroll: RenderSettings,
) {
    init {
        require((text == null) != (script == null)) { "JobSpec needs exactly one of text or script" }
    }
}

data class RenderSettings(
    val width: Int,
    val height: Int,
    val fps: Int,
    /** Spec: `Vertical Position = startY - (Current Time * scrollSpeed)`. */
    val startY: Double,
    val scrollSpeed: Double,
    val backgroundColor: Color,
    val textColor: Color,
    val fontName: String,
    val fontSize: Int,
    val margin: Int,
    val fadeSeconds: Double,
)
