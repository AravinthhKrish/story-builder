package com.lucy.storybuilder.pipeline

/** Output geometry and encoder quality. Shorts default: vertical 720x1280, 30 fps, "medium" quality. */
data class VideoFormat(
    val width: Int = 720,
    val height: Int = 1280,
    val fps: Int = 30,
    /** x264 constant rate factor: lower is better quality and bigger files (18 ≈ visually lossless, 23 = medium). */
    val crf: Int = 23,
    /** x264 speed/efficiency trade-off; `veryfast` keeps 60 s of 720p well inside the delivery budget. */
    val preset: String = "veryfast",
)
