package com.lucy.storybuilder.skills

import com.fasterxml.jackson.annotation.JsonProperty
import com.lucy.storybuilder.pipeline.VideoFormat
import com.lucy.storybuilder.pipeline.timeline.CameraMove

enum class RendererType {
    /** AI illustration per scene + camera moves + captions (Shorts style). */
    @JsonProperty("illustrated")
    ILLUSTRATED,

    /** The original renderer: scrolling text on a plain background, no AI involved. */
    @JsonProperty("text-scroll")
    TEXT_SCROLL,
}

/**
 * A style skill: a named preset of pre-filled prompts and look settings, loaded from
 * `skills/<name>/skill.yaml` plus `director.md` (director system prompt) and `image.md` (image
 * prompt template). Placeholders are `{{name}}`; see [PromptTemplate].
 */
data class Skill(
    val name: String,
    val description: String,
    val renderer: RendererType,
    val format: VideoFormat = VideoFormat(),
    /** Style phrase that opens every image prompt (`{{style}}`). */
    val style: String = "",
    /** Things to keep out of images, for providers that accept a negative prompt. */
    val negative: String = "",
    val wordsPerMinute: Int? = null,
    /** Default voice per TTS engine name (`say`, `piper`, `espeak-ng`). */
    val voices: Map<String, String> = emptyMap(),
    val cameraMoves: List<CameraMove> = CameraMove.entries,
    val scenes: SceneLimits = SceneLimits(),
    /** Narration word budget across the whole script; ~130 words ≈ 55 s at 150 wpm with pauses. */
    val maxWords: Int = 130,
    val transitionSeconds: Double = 0.4,
    val image: ImageSettings = ImageSettings(),
    val caption: CaptionStyle = CaptionStyle(),
    val directorPrompt: String = "",
    val imageTemplate: String = "",
) {
    val isIllustrated: Boolean get() = renderer == RendererType.ILLUSTRATED
}

data class SceneLimits(
    val min: Int = 3,
    val max: Int = 8,
)

/** Size requested from the image model: a little larger than the frame, leaving room for camera moves. */
data class ImageSettings(
    val width: Int = 768,
    val height: Int = 1344,
    val steps: Int = 4,
)

data class CaptionStyle(
    val fontName: String = "SansSerif",
    val fontSize: Int = 46,
    val textColor: String = "#FFFFFF",
    val outlineColor: String = "#000000",
    val boxColor: String = "#000000",
    val boxOpacity: Double = 0.35,
    /** Vertical centre of the caption as a fraction of frame height. */
    val positionY: Double = 0.8,
    /** Words shown at once. */
    val maxWords: Int = 5,
)
