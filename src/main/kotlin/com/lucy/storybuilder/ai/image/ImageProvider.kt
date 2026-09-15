package com.lucy.storybuilder.ai.image

import java.awt.image.BufferedImage

/** Generates one scene illustration. Implementations must be thread-safe: scenes are requested in parallel. */
interface ImageProvider {
    /** e.g. "fal fal-ai/flux/schnell" — for logs and job notes. */
    val description: String

    fun generate(request: ImageRequest): BufferedImage
}

data class ImageRequest(
    val prompt: String,
    /** Honoured only by providers that support negative prompts (FLUX-schnell does not). */
    val negative: String,
    val width: Int,
    val height: Int,
    /** One seed per script keeps the style and characters consistent across scenes. */
    val seed: Long,
    val steps: Int,
    /** Short human label (the scene setting); placeholder art prints it. */
    val label: String,
)

class ImageGenerationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
