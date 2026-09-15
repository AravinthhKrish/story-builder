package com.lucy.storybuilder.api

import com.lucy.storybuilder.CANNED_SCRIPT_JSON
import com.lucy.storybuilder.FakeLlm
import com.lucy.storybuilder.ai.llm.LlmClient
import com.lucy.storybuilder.process.Ffmpeg
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestClient
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeBytes
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The illustrated (Shorts) pipeline end to end over HTTP, with the LLM faked, placeholder art and
 * silent TTS so it runs offline: one-shot and staged flows, SLA reporting, auth.
 */
@EnabledIf("ffmpegInstalled")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "storybuilder.tts.engine=silent",
        "storybuilder.images.provider=placeholder",
        "storybuilder.work-dir=build/test-work",
        "storybuilder.security.api-keys=test-key",
    ],
)
class IllustratedStoryIntegrationTest {
    @TestConfiguration
    class FakeDirector {
        @Bean
        @Primary
        fun fakeLlm(): LlmClient = FakeLlm { CANNED_SCRIPT_JSON }
    }

    @Value("\${local.server.port}")
    private var port: Int = 0

    @Autowired
    private lateinit var ffmpeg: Ffmpeg

    private val client by lazy { client("test-key") }

    private fun client(key: String?) =
        RestClient
            .builder()
            .baseUrl("http://localhost:$port")
            .defaultStatusHandler({ true }) { _, _ -> }
            .apply { b -> key?.let { b.defaultHeader("X-API-Key", it) } }
            .build()

    private fun post(
        path: String,
        body: Any?,
    ): ResponseEntity<Map<*, *>> =
        client
            .post()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .let { spec -> if (body != null) spec.body(body) else spec }
            .retrieve()
            .toEntity(Map::class.java)

    private fun get(path: String): Map<*, *> =
        client
            .get()
            .uri(path)
            .retrieve()
            .body(Map::class.java)!!

    private fun awaitDone(jobId: Any): Map<*, *> {
        val deadline = System.currentTimeMillis() + 120_000
        var job: Map<*, *>
        do {
            Thread.sleep(200)
            job = get("/api/v1/jobs/$jobId")
        } while (job["status"] !in setOf("DONE", "FAILED") && System.currentTimeMillis() < deadline)
        assertEquals("DONE", job["status"], "job: $job")
        return job
    }

    private fun download(jobId: Any): Path {
        val bytes =
            client
                .get()
                .uri("/api/v1/jobs/$jobId/video")
                .retrieve()
                .body(ByteArray::class.java)!!
        return Files.createTempFile("illustrated-", ".mp4").apply { writeBytes(bytes) }
    }

    @Test
    fun `one-shot story renders a vertical cartoon short within the limits`() {
        val created = post("/api/v1/stories", mapOf("text" to "Pip the fox woke early and raced home with Olly the owl."))
        assertEquals(HttpStatus.ACCEPTED, created.statusCode)
        assertEquals("cartoon-storybook", created.body!!["skill"])

        val job = awaitDone(created.body!!["jobId"]!!)
        assertEquals(true, job["slaMet"])
        assertEquals(false, job["degraded"], "notes: ${job["notes"]}")
        assertEquals(3, (job["scenes"] as List<*>).size)
        assertTrue((job["timingsMs"] as Map<*, *>).keys.containsAll(listOf("director", "assets", "render", "total")))
        val seconds = (job["durationSeconds"] as Number).toDouble()
        assertTrue(seconds <= 60.0, "video is $seconds s")

        val video = download(job["jobId"]!!)
        try {
            val v = ffmpeg.probeStreams(video).single { it["codec_type"] == "video" }
            assertEquals("720" to "1280", v["width"] to v["height"])
            assertEquals("30/1", v["r_frame_rate"])
            assertEquals(seconds, ffmpeg.probeDurationSeconds(video), 0.25)

            // A mid-scene frame has a picture (not black) and a bright caption in the lower third.
            val png = Files.createTempFile("frame-", ".png")
            ffmpeg.run("-ss", "2.0", "-i", video.toString(), "-frames:v", "1", "-update", "1", png.toString())
            val frame = ImageIO.read(png.toFile())
            png.deleteIfExists()
            val luminance = { x: Int, y: Int -> frame.getRGB(x, y).let { ((it shr 16 and 255) + (it shr 8 and 255) + (it and 255)) / 3 } }
            assertTrue(luminance(360, 300) > 20, "picture should be visible")
            val captionBand = (950..1110).flatMap { y -> (60..660 step 4).map { x -> luminance(x, y) } }
            assertTrue(captionBand.count { it > 200 } > 50, "caption text should be drawn in the lower third")
        } finally {
            video.deleteIfExists()
        }

        val script = get("/api/v1/scripts/${job["scriptId"]}")
        assertEquals("The Fox and the Owl", script["title"])
    }

    @Test
    fun `staged flow - create, edit, then render the script`() {
        val created = post("/api/v1/scripts", mapOf("text" to "Pip and Olly race home.", "skill" to "watercolor-bedtime"))
        assertEquals(HttpStatus.CREATED, created.statusCode)
        assertNotNull(created.headers.location)
        val script = created.body!!
        val id = script["id"]
        assertEquals("watercolor-bedtime", script["skill"])
        assertTrue(((script["scenes"] as List<*>).first() as Map<*, *>)["imagePrompt"].toString().startsWith("Gentle watercolor"))

        val edited =
            client
                .put()
                .uri("/api/v1/scripts/$id")
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    mapOf(
                        "title" to "Edited",
                        "scenes" to
                            listOf(
                                mapOf(
                                    "narration" to "An edited first scene.",
                                    "setting" to "moonlit pond",
                                    "camera" to "pan_up",
                                    "characters" to listOf("pip"),
                                ),
                                mapOf("narration" to "And a gentle ending.", "setting" to "starry sky", "camera" to "zoom-out"),
                            ),
                    ),
                ).retrieve()
                .toEntity(Map::class.java)
        assertEquals(HttpStatus.OK, edited.statusCode)
        val scenes = edited.body!!["scenes"] as List<*>
        assertEquals(2, scenes.size)
        val first = scenes.first() as Map<*, *>
        assertEquals("pan_up", first["camera"])
        assertTrue("moonlit pond" in first["imagePrompt"].toString() && "Pip (" in first["imagePrompt"].toString())

        val render = post("/api/v1/scripts/$id/render", null)
        assertEquals(HttpStatus.ACCEPTED, render.statusCode)
        val job = awaitDone(render.body!!["jobId"]!!)
        assertEquals(listOf("An edited first scene.", "And a gentle ending."), (job["scenes"] as List<*>).map { (it as Map<*, *>)["text"] })
        assertEquals(id, job["scriptId"])
    }

    @Test
    fun `editing only characters rebuilds generated prompts but keeps hand-written ones`() {
        val script = post("/api/v1/scripts", mapOf("text" to "Pip and Olly race home.")).body!!
        val id = script["id"]
        val scenes = script["scenes"] as List<*>

        fun put(body: Map<String, Any>) =
            client
                .put()
                .uri("/api/v1/scripts/$id")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map::class.java)!!

        // GET -> edit one prompt by hand -> PUT: the echoed generated prompts stay generated.
        val withCustom =
            put(
                mapOf(
                    "scenes" to
                        scenes.mapIndexed { i, s ->
                            val scene = s as Map<*, *>
                            scene.filterKeys { it != "imagePromptCustom" }.plus(
                                "imagePrompt" to if (i == 0) "a hand-written prompt" else scene["imagePrompt"],
                            )
                        },
                ),
            )
        val customScenes = withCustom["scenes"] as List<*>
        assertEquals(listOf(true, false, false), customScenes.map { (it as Map<*, *>)["imagePromptCustom"] })

        // Characters-only PUT: every generated prompt now carries the new look.
        val recast =
            put(
                mapOf(
                    "characters" to
                        listOf(
                            mapOf("id" to "pip", "name" to "Pip", "look" to "tall purple fox in a yellow raincoat"),
                            mapOf("id" to "olly", "name" to "Olly", "look" to "round grey owl with big amber eyes and tiny glasses"),
                        ),
                ),
            )
        val prompts = (recast["scenes"] as List<*>).map { (it as Map<*, *>)["imagePrompt"].toString() }
        assertEquals("a hand-written prompt", prompts[0])
        assertTrue("Pip (tall purple fox in a yellow raincoat)" in prompts[2], prompts[2])
        assertTrue(prompts.none { "small orange fox" in it }, "old look must be gone: $prompts")
    }

    @Test
    fun `skills are listed and bad script edits are rejected`() {
        val skills =
            client
                .get()
                .uri("/api/v1/skills")
                .retrieve()
                .body(List::class.java)!!
        assertEquals(5, skills.size)
        assertTrue(skills.any { (it as Map<*, *>)["name"] == "cartoon-storybook" && it["isDefault"] == true })

        val id = post("/api/v1/scripts", mapOf("text" to "A tiny tale.")).body!!["id"]
        val badCamera =
            client
                .put()
                .uri("/api/v1/scripts/$id")
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("scenes" to listOf(mapOf("narration" to "x", "camera" to "barrel_roll"))))
                .retrieve()
                .toBodilessEntity()
        assertEquals(HttpStatus.BAD_REQUEST, badCamera.statusCode)
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/v1/stories", mapOf("text" to "x", "skill" to "nope")).statusCode)
        assertEquals(
            HttpStatus.NOT_FOUND,
            client
                .get()
                .uri("/api/v1/scripts/00000000-0000-0000-0000-000000000000")
                .retrieve()
                .toBodilessEntity()
                .statusCode,
        )
    }

    @Test
    fun `api requires the key while health stays open`() {
        val anonymous = client(null)
        assertEquals(
            HttpStatus.UNAUTHORIZED,
            anonymous
                .get()
                .uri("/api/v1/skills")
                .retrieve()
                .toBodilessEntity()
                .statusCode,
        )
        assertEquals(
            HttpStatus.UNAUTHORIZED,
            client("wrong")
                .get()
                .uri("/api/v1/skills")
                .retrieve()
                .toBodilessEntity()
                .statusCode,
        )
        assertEquals(
            HttpStatus.OK,
            anonymous
                .get()
                .uri("/actuator/health")
                .retrieve()
                .toBodilessEntity()
                .statusCode,
        )
    }

    companion object {
        @JvmStatic
        fun ffmpegInstalled(): Boolean = runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false)
    }
}
