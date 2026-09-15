package com.lucy.storybuilder.pipeline.timeline

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/** Camera moves over a still illustration ("Ken Burns"), chosen per scene by the director. */
enum class CameraMove {
    ZOOM_IN,
    ZOOM_OUT,
    PAN_LEFT,
    PAN_RIGHT,
    PAN_UP,
    ;

    @JsonValue
    fun json(): String = name.lowercase()

    companion object {
        @JvmStatic
        @JsonCreator
        fun parse(value: String): CameraMove =
            entries.firstOrNull { it.name.equals(value.trim().replace('-', '_').replace(' ', '_'), ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown camera move '$value'; use one of ${entries.map { it.json() }}")

        fun parseOrNull(value: String?): CameraMove? = value?.let { runCatching { parse(it) }.getOrNull() }
    }
}

/**
 * Camera state at a point in a scene. [zoom] multiplies the cover-fit scale (1.0 = image just fills
 * the frame). [panX]/[panY] say where the camera looks inside the spare image area that zoom creates:
 * -1 = left/top edge, 0 = centre, +1 = right/bottom edge.
 */
data class CameraFrame(
    val zoom: Double,
    val panX: Double,
    val panY: Double,
)

/**
 * Motion formulas for camera moves, in the same spirit as [LinearScroll]: pure functions of time.
 * Progress is eased (smoothstep) so moves start and stop gently instead of jerking at cuts.
 */
object CameraMotion {
    const val MAX_ZOOM = 1.15

    /** Pans happen at a constant zoom so there is image to travel across. */
    const val PAN_ZOOM = 1.12

    fun at(
        move: CameraMove,
        progress: Double,
    ): CameraFrame {
        val p = ease(progress.coerceIn(0.0, 1.0))
        return when (move) {
            CameraMove.ZOOM_IN -> CameraFrame(1.0 + (MAX_ZOOM - 1.0) * p, 0.0, 0.0)
            CameraMove.ZOOM_OUT -> CameraFrame(MAX_ZOOM - (MAX_ZOOM - 1.0) * p, 0.0, 0.0)
            CameraMove.PAN_LEFT -> CameraFrame(PAN_ZOOM, 1.0 - 2.0 * p, 0.0) // right edge → left edge
            CameraMove.PAN_RIGHT -> CameraFrame(PAN_ZOOM, -1.0 + 2.0 * p, 0.0) // left edge → right edge
            CameraMove.PAN_UP -> CameraFrame(PAN_ZOOM, 0.0, 1.0 - 2.0 * p) // bottom → top
        }
    }

    fun ease(x: Double): Double = x * x * (3 - 2 * x)
}
