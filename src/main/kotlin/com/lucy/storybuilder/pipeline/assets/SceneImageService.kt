package com.lucy.storybuilder.pipeline.assets

import com.lucy.storybuilder.ai.image.ImageProvider
import com.lucy.storybuilder.ai.image.ImageRequest
import com.lucy.storybuilder.ai.image.PlaceholderImageProvider
import com.lucy.storybuilder.config.StoryBuilderProperties
import com.lucy.storybuilder.script.StoryScript
import com.lucy.storybuilder.skills.Skill
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.awt.image.BufferedImage
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

data class SceneImages(
    val images: List<BufferedImage>,
    /** One note per scene that fell back to placeholder art, with the reason. */
    val notes: List<String>,
)

/**
 * Stage 2 (visual layer) for illustrated skills: one picture per scene, all requested in parallel
 * (bounded by `storybuilder.images.concurrency`), each retried and, if it still fails, replaced by
 * placeholder art — a failed image degrades one scene instead of failing the whole video.
 */
@Service
class SceneImageService(
    private val provider: ImageProvider,
    private val placeholder: PlaceholderImageProvider,
    private val props: StoryBuilderProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun generate(
        script: StoryScript,
        skill: Skill,
    ): SceneImages {
        val permits = Semaphore(props.images.concurrency.coerceAtLeast(1))
        val results =
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                script.scenes
                    .mapIndexed { i, scene ->
                        val request =
                            ImageRequest(
                                prompt = scene.imagePrompt,
                                negative = skill.negative,
                                width = skill.image.width,
                                height = skill.image.height,
                                seed = script.seed,
                                steps = skill.image.steps,
                                label = scene.setting.ifBlank { scene.narration },
                            )
                        executor.submit<Pair<BufferedImage, String?>> {
                            permits.acquire()
                            try {
                                generateOne(i, request)
                            } finally {
                                permits.release()
                            }
                        }
                    }.map { it.get() }
            }
        return SceneImages(results.map { it.first }, results.mapNotNull { it.second })
    }

    private fun generateOne(
        index: Int,
        request: ImageRequest,
    ): Pair<BufferedImage, String?> {
        var lastError: Exception? = null
        repeat(props.images.retries + 1) { attempt ->
            try {
                val started = System.nanoTime()
                val image = provider.generate(request)
                log.debug(
                    "Scene {} image from {} in {} ms (attempt {})",
                    index,
                    provider.description,
                    (System.nanoTime() - started) / 1_000_000,
                    attempt + 1,
                )
                return image to null
            } catch (e: Exception) {
                lastError = e
                log.warn("Scene {} image attempt {} failed: {}", index, attempt + 1, e.message)
            }
        }
        val note = "Scene $index: image generation failed (${lastError?.message}); used placeholder art"
        return placeholder.generate(request) to note
    }
}
