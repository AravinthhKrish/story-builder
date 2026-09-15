package com.lucy.storybuilder.pipeline.timeline

import java.util.Locale

data class FittedDurations(
    /** Narration speed-up to apply (1.0 = unchanged). */
    val speed: Double,
    /** Scenes kept, from the start. */
    val sceneCount: Int,
    /** Slot length per kept scene, in seconds. */
    val slots: List<Double>,
    val notes: List<String>,
)

/**
 * Keeps a video within the Shorts length limit. Each scene's slot is its narration plus a pause
 * (never shorter than its minimum). If the total is too long, narration is sped up as little as
 * needed, up to `maxSpeedup`; if that still isn't enough, trailing scenes are dropped.
 */
object DurationBudget {
    /** Headroom for rounding every scene up to a whole frame. */
    private const val FRAME_ROUNDING_HEADROOM = 0.5

    fun fit(
        audioSeconds: List<Double>,
        minSlotSeconds: List<Double>,
        paddingSeconds: Double,
        maxTotalSeconds: Double,
        maxSpeedup: Double,
    ): FittedDurations {
        require(audioSeconds.isNotEmpty() && audioSeconds.size == minSlotSeconds.size) { "one minimum per scene" }
        val target = maxTotalSeconds - FRAME_ROUNDING_HEADROOM

        fun slots(
            speed: Double,
            n: Int,
        ) = (0 until n).map { maxOf(minSlotSeconds[it], audioSeconds[it] / speed + paddingSeconds) }

        val all = audioSeconds.size
        val natural = slots(1.0, all)
        if (natural.sum() <= target) return FittedDurations(1.0, all, natural, emptyList())

        if (slots(maxSpeedup, all).sum() <= target) {
            // Smallest speed-up that fits: total length is monotonic in speed, so bisect.
            var lo = 1.0
            var hi = maxSpeedup
            repeat(30) {
                val mid = (lo + hi) / 2
                if (slots(mid, all).sum() <= target) hi = mid else lo = mid
            }
            val note = "Narration sped up ${fmt(hi)}x to fit ${fmt(maxTotalSeconds)} s (was ${fmt(natural.sum())} s)"
            return FittedDurations(hi, all, slots(hi, all), listOf(note))
        }

        var keep = all
        while (keep > 1 && slots(maxSpeedup, keep).sum() > target) keep--
        val notes = mutableListOf<String>()
        if (keep < all) {
            notes += "Story too long for ${fmt(maxTotalSeconds)} s: narration sped up ${fmt(maxSpeedup)}x and " +
                "the last ${all - keep} of $all scenes dropped"
        }
        var kept = slots(maxSpeedup, keep)
        if (kept.sum() > target) {
            // A single scene still too long even at max speed: its slot (and so its narration) is cut off.
            notes += "First scene is ${fmt(kept.sum())} s even at ${fmt(maxSpeedup)}x; narration trimmed to ${fmt(target)} s"
            kept = listOf(target)
        }
        return FittedDurations(maxSpeedup, keep, kept, notes)
    }

    private fun fmt(value: Double) = String.format(Locale.ROOT, "%.2f", value)
}
