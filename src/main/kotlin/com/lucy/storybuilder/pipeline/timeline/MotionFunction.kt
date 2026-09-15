package com.lucy.storybuilder.pipeline.timeline

/** Maps time since the scene started to the text block's vertical position in canvas pixels. */
fun interface MotionFunction {
    fun positionAt(sceneSeconds: Double): Double
}

/**
 * The spec's motion formula: `Vertical Position = startY - (Current Time * speed)`, defaults 500 and
 * 15 px/s. The position is the vertical centre of the scene's text block, so the text starts just
 * above the middle of a 1080 px canvas and drifts upward. Evaluated per frame, 24 times a second.
 */
class LinearScroll(
    private val startY: Double,
    private val speed: Double,
) : MotionFunction {
    override fun positionAt(sceneSeconds: Double): Double = startY - sceneSeconds * speed
}
