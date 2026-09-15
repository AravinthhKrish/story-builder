package com.lucy.storybuilder.pipeline

import java.awt.Color

/** Everything one render needs: request options already merged over the configured defaults. */
data class JobSpec(
    val text: String,
    val wordsPerMinute: Int,
    val voice: String?,
    val render: RenderSettings,
)

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
