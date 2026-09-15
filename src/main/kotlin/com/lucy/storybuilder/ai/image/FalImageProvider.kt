package com.lucy.storybuilder.ai.image

import com.lucy.storybuilder.ai.AiHttp
import com.lucy.storybuilder.config.StoryBuilderProperties
import org.springframework.http.MediaType
import tools.jackson.databind.JsonNode
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.net.URI
import java.time.Duration
import java.util.Base64
import javax.imageio.ImageIO

/**
 * fal.ai synchronous inference (`POST https://fal.run/<model>`), FLUX.1-schnell by default: 4 steps,
 * ~1–3 s per image. With `sync_mode` the image comes back inline as a data URI, so there is no second
 * download. Auth is `Authorization: Key <FAL_KEY>`; the key never appears in logs or errors.
 */
class FalImageProvider(
    private val config: StoryBuilderProperties.Fal,
    timeout: Duration,
) : ImageProvider {
    private val apiKey = requireNotNull(config.apiKey?.takeIf { it.isNotBlank() }) { "fal.ai needs an API key (FAL_KEY)" }
    private val client = AiHttp.client(config.baseUrl, timeout)
    override val description = "fal ${config.model}"

    override fun generate(request: ImageRequest): BufferedImage {
        val body =
            mapOf(
                "prompt" to request.prompt,
                "image_size" to mapOf("width" to request.width, "height" to request.height),
                "num_inference_steps" to request.steps,
                "seed" to (request.seed and Int.MAX_VALUE.toLong()),
                "num_images" to 1,
                "output_format" to "jpeg",
                "enable_safety_checker" to true,
                "sync_mode" to config.syncMode,
            )
        val reply =
            try {
                client
                    .post()
                    .uri("/{model}", mapOf("model" to config.model))
                    .header("Authorization", "Key $apiKey")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode::class.java)
            } catch (e: Exception) {
                throw ImageGenerationException(AiHttp.describe("fal.ai", e), e)
            } ?: throw ImageGenerationException("fal.ai returned an empty response")

        if (reply.path("has_nsfw_concepts").path(0).asBoolean(false)) {
            throw ImageGenerationException("fal.ai safety checker blocked the image")
        }
        val url =
            reply
                .path("images")
                .path(0)
                .path("url")
                .asString()
        if (url.isBlank()) throw ImageGenerationException("fal.ai response had no image")
        return decode(download(url))
    }

    private fun download(url: String): ByteArray =
        if (url.startsWith("data:")) {
            Base64.getDecoder().decode(url.substringAfter(","))
        } else {
            try {
                client
                    .get()
                    .uri(URI.create(url))
                    .retrieve()
                    .body(ByteArray::class.java)
            } catch (e: Exception) {
                throw ImageGenerationException(AiHttp.describe("fal.ai image download", e), e)
            } ?: throw ImageGenerationException("fal.ai image download was empty")
        }

    private fun decode(bytes: ByteArray): BufferedImage =
        ImageIO.read(ByteArrayInputStream(bytes)) ?: throw ImageGenerationException("fal.ai image could not be decoded")
}
