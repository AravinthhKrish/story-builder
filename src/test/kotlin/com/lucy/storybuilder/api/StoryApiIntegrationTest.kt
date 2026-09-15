package com.lucy.storybuilder.api

import com.lucy.storybuilder.process.Ffmpeg
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestClient
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeBytes
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End to end over HTTP: submit a story, poll the job, download the .mp4 and inspect it with ffprobe.
 * Uses the silent TTS engine so it runs anywhere ffmpeg does; skipped when ffmpeg is not installed.
 */
@EnabledIf("ffmpegInstalled")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "storybuilder.tts.engine=silent",
        "storybuilder.images.provider=placeholder",
        "storybuilder.work-dir=build/test-work",
    ],
)
class StoryApiIntegrationTest {
    @Value("\${local.server.port}")
    private var port: Int = 0

    @Autowired
    private lateinit var ffmpeg: Ffmpeg

    private val client by lazy {
        RestClient
            .builder()
            .baseUrl("http://localhost:$port/api/v1")
            .defaultStatusHandler({ true }) { _, _ -> } // assert on status codes instead of throwing
            .build()
    }

    private fun post(body: Map<String, Any>): ResponseEntity<Map<*, *>> =
        client
            .post()
            .uri("/stories")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(Map::class.java)

    private fun job(id: Any): Map<*, *> =
        client
            .get()
            .uri("/jobs/{id}", id)
            .retrieve()
            .body(Map::class.java)!!

    @Test
    fun `text-scroll story is rendered to a 1080p 24fps h264 mp4 with narration`() {
        val story = "The fox woke before dawn.\n\nShe ran through the silver forest.\n\nAnd then she was home."

        val created = post(mapOf("text" to story, "skill" to "text-scroll"))
        assertEquals(HttpStatus.ACCEPTED, created.statusCode)
        assertNotNull(created.headers.location)
        val id = created.body!!["jobId"]!!

        var status = job(id)
        val deadline = System.currentTimeMillis() + 120_000
        while (status["status"] !in setOf("DONE", "FAILED") && System.currentTimeMillis() < deadline) {
            Thread.sleep(250)
            status = job(id)
        }
        assertEquals("DONE", status["status"], "job ended as $status")
        assertEquals(3, (status["scenes"] as List<*>).size)
        val expectedSeconds = (status["durationSeconds"] as Number).toDouble()

        val video =
            client
                .get()
                .uri("/jobs/{id}/video", id)
                .retrieve()
                .toEntity(ByteArray::class.java)
        assertEquals(HttpStatus.OK, video.statusCode)
        assertEquals("video/mp4", video.headers.contentType.toString())

        val file = Files.createTempFile("story-", ".mp4").apply { writeBytes(video.body!!) }
        try {
            val streams = ffmpeg.probeStreams(file)
            val v = streams.single { it["codec_type"] == "video" }
            val a = streams.single { it["codec_type"] == "audio" }
            assertEquals("h264", v["codec_name"])
            assertEquals("1920", v["width"])
            assertEquals("1080", v["height"])
            assertEquals("24/1", v["r_frame_rate"])
            assertEquals("yuv420p", v["pix_fmt"])
            assertEquals("aac", a["codec_name"])
            assertEquals(expectedSeconds, ffmpeg.probeDurationSeconds(file), 0.25)
        } finally {
            file.deleteIfExists()
        }
    }

    @Test
    fun `invalid requests are rejected as problem details`() {
        assertEquals(HttpStatus.BAD_REQUEST, post(mapOf("text" to "  ")).statusCode)
        assertEquals(
            HttpStatus.BAD_REQUEST,
            post(mapOf("text" to "Hi.", "skill" to "text-scroll", "options" to mapOf("width" to 1921))).statusCode,
        )

        val badColor = post(mapOf("text" to "Hi.", "options" to mapOf("textColor" to "red")))
        assertEquals(HttpStatus.BAD_REQUEST, badColor.statusCode)
        assertTrue(badColor.headers.contentType!!.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
    }

    @Test
    fun `unknown job is 404`() {
        val response =
            client
                .get()
                .uri("/jobs/{id}", "00000000-0000-0000-0000-000000000000")
                .retrieve()
                .toBodilessEntity()
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    companion object {
        @JvmStatic
        fun ffmpegInstalled(): Boolean =
            runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false)
    }
}
