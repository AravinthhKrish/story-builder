package com.lucy.storybuilder.ai.image

import com.lucy.storybuilder.config.StoryBuilderProperties
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** FalImageProvider against a local stub of fal.run: request shape, auth header, error handling. */
class FalImageProviderTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }
    private val baseUrl = "http://127.0.0.1:${server.address.port}"
    private val json = JsonMapper.builder().build()
    private var seenAuth: String? = null
    private var seenBody: Map<*, *>? = null

    private val jpeg: ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(BufferedImage(64, 112, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()

    @AfterEach
    fun stop() = server.stop(0)

    private fun respond(
        status: Int,
        body: String,
    ) {
        server.createContext("/fal-ai/flux/schnell") { ex ->
            seenAuth = ex.requestHeaders.getFirst("Authorization")
            seenBody = json.readValue(ex.requestBody.readAllBytes(), Map::class.java)
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
    }

    private fun provider(
        syncMode: Boolean = true,
        key: String = "secret-key-123",
    ) = FalImageProvider(StoryBuilderProperties.Fal(baseUrl = baseUrl, apiKey = key, syncMode = syncMode), Duration.ofSeconds(5))

    private val request = ImageRequest("a fox", "text", 768, 1344, seed = 42, steps = 4, label = "forest")

    @Test
    fun `sends fal's request shape with Key auth and decodes the inline image`() {
        val dataUri = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg)
        respond(200, """{"images":[{"url":"$dataUri","width":64,"height":112}],"seed":42,"has_nsfw_concepts":[false]}""")

        val image = provider().generate(request)

        assertEquals(64 to 112, image.width to image.height)
        assertEquals("Key secret-key-123", seenAuth)
        assertEquals("a fox", seenBody!!["prompt"])
        assertEquals(mapOf("width" to 768, "height" to 1344), seenBody!!["image_size"])
        assertEquals(4, seenBody!!["num_inference_steps"])
        assertEquals(42, seenBody!!["seed"])
        assertEquals(true, seenBody!!["sync_mode"])
        assertFalse("negative_prompt" in seenBody!!, "FLUX-schnell has no negative prompt")
    }

    @Test
    fun `downloads the image when fal returns a url`() {
        server.createContext("/files/out.jpg") { ex ->
            ex.sendResponseHeaders(200, jpeg.size.toLong())
            ex.responseBody.use { it.write(jpeg) }
        }
        respond(200, """{"images":[{"url":"$baseUrl/files/out.jpg"}],"has_nsfw_concepts":[false]}""")

        assertEquals(64, provider(syncMode = false).generate(request).width)
        assertEquals(false, seenBody!!["sync_mode"])
    }

    @Test
    fun `http errors are reported without leaking the key`() {
        respond(401, """{"detail":"Invalid API key"}""")
        val e = assertThrows<ImageGenerationException> { provider().generate(request) }
        assertTrue("HTTP 401" in e.message!!, e.message)
        assertFalse("secret-key-123" in e.message!!)
    }

    @Test
    fun `safety-blocked and empty results are errors`() {
        respond(200, """{"images":[{"url":"data:image/jpeg;base64,AAAA"}],"has_nsfw_concepts":[true]}""")
        assertThrows<ImageGenerationException> { provider().generate(request) }
    }

    @Test
    fun `a missing key is rejected up front`() {
        assertThrows<IllegalArgumentException> { provider(key = " ") }
    }
}
