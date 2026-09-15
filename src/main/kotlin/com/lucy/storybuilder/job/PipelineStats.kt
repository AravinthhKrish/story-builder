package com.lucy.storybuilder.job

import org.springframework.stereotype.Component

/**
 * Running averages of how long illustrated jobs take to render and to generate assets on *this*
 * machine. The delivery budget uses them to decide how long the director may think: whatever the
 * render and assets are expected to need is reserved first. Starts from conservative guesses and
 * converges after a job or two.
 */
@Component
class PipelineStats {
    @Volatile private var renderMs: Double = 6.0

    @Volatile private var assets: Double = 8_000.0

    /** Wall-clock render + encode time per output frame, parallel drawing included. */
    val renderMsPerFrame: Double get() = renderMs

    val assetsMs: Double get() = assets

    @Synchronized
    fun recordRender(
        renderMs: Long,
        frames: Int,
    ) {
        if (frames > 0) this.renderMs = ewma(this.renderMs, renderMs.toDouble() / frames)
    }

    @Synchronized
    fun recordAssets(ms: Long) {
        assets = ewma(assets, ms.toDouble())
    }

    private fun ewma(
        current: Double,
        sample: Double,
    ) = current + ALPHA * (sample - current)

    private companion object {
        const val ALPHA = 0.4
    }
}
