package com.lucy.storybuilder.e2e

import com.lucy.storybuilder.config.StoryBuilderProperties
import com.lucy.storybuilder.process.Ffmpeg
import com.lucy.storybuilder.process.ProcessRunner
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestClient
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeBytes
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Black-box tests against a running deployment (Docker, a Linux host, or bootRun). Only HTTP goes to
 * the service; the returned videos are inspected locally with ffmpeg/ffprobe.
 *
 *   ./gradlew e2eTest -Pe2e.baseUrl=http://localhost:8080
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeployedServiceE2ETest {
    private val baseUrl = System.getProperty("e2e.baseUrl", "http://localhost:8080").trimEnd('/')
    private val apiKey = System.getProperty("e2e.apiKey").orEmpty()

    /** Set when the target has a working LLM (and optionally FAL_KEY): then fallbacks count as failures. */
    private val requireAi = System.getProperty("e2e.requireAi").toBoolean()
    private val processRunner = ProcessRunner()
    private val ffmpeg = Ffmpeg(StoryBuilderProperties(), processRunner)
    private val downloads: Path = Files.createTempDirectory("story-e2e-")

    private val client = client(apiKey)

    private fun client(key: String) =
        RestClient
            .builder()
            .baseUrl(baseUrl)
            .defaultStatusHandler({ true }) { _, _ -> } // assert on status codes instead of throwing
            .apply { b -> if (key.isNotEmpty()) b.defaultHeader("X-API-Key", key) }
            .build()

    /** The original look: its checks below are exact (1080p, 24 fps, 15 px/s scroll). */
    private fun textScroll(body: Map<String, Any>) = body + ("skill" to "text-scroll")

    private data class Rendered(
        val id: String,
        val job: Map<*, *>,
        val video: Path,
    )

    /** Scene 0 has 15 words (~6 s), long enough to measure scrolling away from the fades. */
    private val story by lazy {
        render(
            textScroll(
                mapOf(
                    "text" to
                        "The fox woke before dawn. She ran through the silver forest, past the sleeping river.\n\n" +
                        "The owl watched her go.\n\n" +
                        "And then she was home.",
                ),
            ),
        )
    }

    @BeforeAll
    fun serviceIsUp() {
        val health =
            runCatching {
                client
                    .get()
                    .uri("/actuator/health")
                    .retrieve()
                    .toEntity(Map::class.java)
            }.getOrElse { fail("story-builder is not reachable at $baseUrl (${it.message}). Start it first, e.g. docker compose up -d") }
        assertEquals(HttpStatus.OK, health.statusCode, "health endpoint")
        assertEquals("UP", health.body?.get("status"), "health status")
        println("E2E target: $baseUrl")
    }

    @AfterAll
    @OptIn(ExperimentalPathApi::class)
    fun cleanUp() {
        downloads.deleteRecursively()
    }

    @Test
    fun `text-scroll story renders to the spec - h264 1920x1080 at 24fps with aac narration`() {
        val (_, job, video) = story
        assertEquals(3, (job["scenes"] as List<*>).size, "scenes")

        val streams = ffmpeg.probeStreams(video)
        val v = streams.single { it["codec_type"] == "video" }
        val a = streams.single { it["codec_type"] == "audio" }
        assertEquals("h264", v["codec_name"])
        assertEquals("1920" to "1080", v["width"] to v["height"])
        assertEquals("24/1", v["r_frame_rate"])
        assertEquals("yuv420p", v["pix_fmt"])
        assertEquals("aac", a["codec_name"])

        val expected = (job["durationSeconds"] as Number).toDouble()
        assertEquals(expected, ffmpeg.probeDurationSeconds(video), 0.25, "video length matches the reported timeline")
    }

    @Test
    fun `narration is audible speech, not silence`() {
        val volume =
            processRunner
                .run(
                    listOf(
                        "ffmpeg",
                        "-hide_banner",
                        "-nostats",
                        "-i",
                        story.video.toString(),
                        "-map",
                        "0:a",
                        "-af",
                        "volumedetect",
                        "-f",
                        "null",
                        "-",
                    ),
                ).stderr
        val mean =
            Regex("mean_volume: (-?[\\d.]+) dB")
                .find(volume)
                ?.groupValues
                ?.get(1)
                ?.toDouble()
                ?: fail("no volumedetect output")
        println("narration mean volume: $mean dB")
        assertTrue(mean > -35.0, "mean volume $mean dB means the TTS produced (near) silence")
    }

    @Test
    fun `text is drawn and scrolls up at 15 px per second`() {
        val at1s = frame(story.video, 1.0)
        val at3s = frame(story.video, 3.0)

        val top1 = topInkRow(at1s) ?: fail("no text visible at t=1s — fonts missing in the deployment?")
        val top3 = topInkRow(at3s) ?: fail("no text visible at t=3s")
        println("text top edge: ${top1}px at 1s -> ${top3}px at 3s")
        assertEquals(30.0, (top1 - top3).toDouble(), 3.0, "expected 500 - t*15 motion: 2 s x 15 px/s = 30 px")
    }

    @Test
    fun `video endpoint serves byte ranges so players can seek`() {
        val partial =
            client
                .get()
                .uri("/api/v1/jobs/{id}/video", story.id)
                .header(HttpHeaders.RANGE, "bytes=0-99")
                .retrieve()
                .toEntity(ByteArray::class.java)
        assertEquals(HttpStatus.PARTIAL_CONTENT, partial.statusCode)
        assertEquals(100, partial.body?.size)
        assertEquals("ftyp", String(partial.body!!, 4, 4), "mp4 header")
    }

    @Test
    fun `custom options - resolution, fps and colours - are honoured`() {
        val (_, _, video) =
            render(
                textScroll(
                    mapOf(
                        "text" to "A short scene in a different size.",
                        "options" to mapOf("width" to 1280, "height" to 720, "fps" to 30, "backgroundColor" to "#203040"),
                    ),
                ),
            )
        val v = ffmpeg.probeStreams(video).single { it["codec_type"] == "video" }
        assertEquals("1280" to "720", v["width"] to v["height"])
        assertEquals("30/1", v["r_frame_rate"])

        val corner = frame(video, 1.0).getRGB(4, 4)
        val (r, g, b) = Triple(corner shr 16 and 0xFF, corner shr 8 and 0xFF, corner and 0xFF)
        assertTrue(
            listOf(r - 0x20, g - 0x30, b - 0x40).all { kotlin.math.abs(it) <= 8 },
            "background rgb($r,$g,$b) should be close to #203040",
        )
    }

    @Test
    fun `jobs beyond the concurrency limit queue and all complete`() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results =
                (1..4)
                    .map { n ->
                        pool.submit<Rendered> { render(textScroll(mapOf("text" to "Parallel story number $n."))) }
                    }.map { it.get() }
            assertEquals(4, results.map { it.id }.toSet().size)
            results.forEach { assertTrue(ffmpeg.probeDurationSeconds(it.video) > 1.0) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `video is 409 while the job is still rendering`() {
        val created =
            post(
                textScroll(
                    mapOf("text" to "This job was only just submitted, so its video cannot exist yet. " + "More words. ".repeat(20)),
                ),
            )
        assertEquals(HttpStatus.ACCEPTED, created.statusCode)
        val early =
            client
                .get()
                .uri("/api/v1/jobs/{id}/video", created.body!!["jobId"])
                .retrieve()
                .toBodilessEntity()
        assertEquals(HttpStatus.CONFLICT, early.statusCode)
    }

    @Test
    fun `invalid requests are rejected with problem details`() {
        val cases =
            mapOf(
                "blank text" to mapOf("text" to "   "),
                "odd width" to mapOf("text" to "Hi.", "options" to mapOf("width" to 1921)),
                "bad colour" to mapOf("text" to "Hi.", "options" to mapOf("textColor" to "red")),
                "fps too high" to mapOf("text" to "Hi.", "options" to mapOf("fps" to 240)),
                "unknown skill" to mapOf("text" to "Hi.", "skill" to "no-such-skill"),
            )
        cases.forEach { (name, body) ->
            val response = post(body)
            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode, name)
            assertTrue(response.headers.contentType!!.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON), "$name content type")
        }
    }

    @Test
    fun `unknown job is 404`() {
        val response =
            client
                .get()
                .uri("/api/v1/jobs/{id}", "00000000-0000-0000-0000-000000000000")
                .retrieve()
                .toBodilessEntity()
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `api key is required when the deployment has one`() {
        assumeTrue(apiKey.isNotEmpty(), "no -Pe2e.apiKey given")
        val anonymous =
            client("")
                .get()
                .uri("/api/v1/skills")
                .retrieve()
                .toBodilessEntity()
        assertEquals(HttpStatus.UNAUTHORIZED, anonymous.statusCode)
        val wrong =
            client("definitely-wrong")
                .get()
                .uri("/api/v1/skills")
                .retrieve()
                .toBodilessEntity()
        assertEquals(HttpStatus.UNAUTHORIZED, wrong.statusCode)
    }

    @Test
    fun `style skills are listed`() {
        val skills =
            client
                .get()
                .uri("/api/v1/skills")
                .retrieve()
                .body(List::class.java)!!
                .map { (it as Map<*, *>)["name"] }
        assertTrue(skills.containsAll(listOf("cartoon-storybook", "anime", "watercolor-bedtime", "comic-book", "text-scroll")), "$skills")
    }

    @Test
    fun `cartoon short is delivered within 60 s as a vertical 720p video of at most 60 s`() {
        val started = System.currentTimeMillis()
        val (_, job, video) =
            render(
                mapOf(
                    "text" to
                        "Pip was a small orange fox who lived in a cosy den under an old oak tree. " +
                        "One day he heard a tiny cry from the river: a baby owl named Olly was stuck on a floating log.\n\n" +
                        "Pip leapt from rock to rock until he reached the log and pulled Olly to safety.\n\n" +
                        "He wrapped his green scarf around her and carried her home. From that day on they were best friends.",
                ),
            )
        val wallSeconds = (System.currentTimeMillis() - started) / 1000.0
        println("cartoon short: wall ${wallSeconds}s, timings ${job["timingsMs"]}, degraded ${job["degraded"]}, notes ${job["notes"]}")

        assertEquals("cartoon-storybook", job["skill"])
        assertEquals(true, job["slaMet"], "service-side delivery time ${job["timingsMs"]}")
        assertTrue(wallSeconds <= 62.0, "client saw ${wallSeconds}s (includes polling)")
        if (requireAi) assertEquals(false, job["degraded"], "fallbacks used: ${job["notes"]}")

        val v = ffmpeg.probeStreams(video).single { it["codec_type"] == "video" }
        assertEquals("720" to "1280", v["width"] to v["height"])
        assertEquals("30/1", v["r_frame_rate"])
        assertTrue(ffmpeg.probeDurationSeconds(video) <= 60.05, "video must be Shorts length")
        assertTrue(meanVolume(video) > -35.0, "narration should be audible")

        val frame = frame(video, 2.0)
        val lum = { x: Int, y: Int -> frame.getRGB(x, y).let { ((it shr 16 and 255) + (it shr 8 and 255) + (it and 255)) / 3 } }
        assertTrue(lum(360, 400) > 15, "picture should be visible, not black")
        val captionBand = (900..1150).flatMap { y -> (40..680 step 4).map { x -> lum(x, y) } }
        assertTrue(captionBand.count { it > 200 } > 40, "caption text should be drawn in the lower third")
    }

    @Test
    fun `staged flow - script, edit, render`() {
        val created =
            client
                .post()
                .uri("/api/v1/scripts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("text" to "A lighthouse keeper guided a lost boat home through the storm.", "skill" to "comic-book"))
                .retrieve()
                .toEntity(Map::class.java)
        assertEquals(HttpStatus.CREATED, created.statusCode)
        val id = created.body!!["id"]
        println("staged script: degraded ${created.body!!["degraded"]}, scenes ${(created.body!!["scenes"] as List<*>).size}")

        val edited =
            client
                .put()
                .uri("/api/v1/scripts/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    mapOf(
                        "scenes" to
                            listOf(
                                mapOf("narration" to "The storm raged.", "camera" to "zoom_in"),
                                mapOf(
                                    "narration" to "The boat came home.",
                                ),
                            ),
                    ),
                ).retrieve()
                .toEntity(Map::class.java)
        assertEquals(HttpStatus.OK, edited.statusCode)

        val job =
            client
                .post()
                .uri("/api/v1/scripts/{id}/render", id)
                .retrieve()
                .toEntity(Map::class.java)
        assertEquals(HttpStatus.ACCEPTED, job.statusCode)
        val done = awaitDone(job.body!!["jobId"].toString())
        assertEquals(listOf("The storm raged.", "The boat came home."), (done["scenes"] as List<*>).map { (it as Map<*, *>)["text"] })
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun post(body: Map<String, Any>): ResponseEntity<Map<*, *>> =
        client
            .post()
            .uri("/api/v1/stories")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(Map::class.java)

    /** Submits a story, waits for DONE, downloads the mp4. */
    private fun render(body: Map<String, Any>): Rendered {
        val created = post(body)
        assertEquals(HttpStatus.ACCEPTED, created.statusCode, "submit: ${created.body}")
        assertNotNull(created.headers.location, "Location header")
        val id = created.body!!["jobId"].toString()

        val job = awaitDone(id)

        val bytes =
            client
                .get()
                .uri("/api/v1/jobs/{id}/video", id)
                .retrieve()
                .toEntity(ByteArray::class.java)
        assertEquals(HttpStatus.OK, bytes.statusCode)
        assertEquals("video/mp4", bytes.headers.contentType.toString())
        val file = downloads.resolve("$id.mp4").apply { writeBytes(bytes.body!!) }
        return Rendered(id, job, file)
    }

    private fun awaitDone(id: String): Map<*, *> {
        val started = System.currentTimeMillis()
        var job: Map<*, *>
        do {
            Thread.sleep(300)
            job =
                client
                    .get()
                    .uri("/api/v1/jobs/{id}", id)
                    .retrieve()
                    .body(Map::class.java)!!
            if (System.currentTimeMillis() - started > 180_000) fail("job $id still ${job["status"]} after 180 s")
        } while (job["status"] !in setOf("DONE", "FAILED"))
        assertEquals("DONE", job["status"], "job $id failed: ${job["error"]}")
        println("job $id: ${job["durationSeconds"]} s of video in ${System.currentTimeMillis() - started} ms")
        return job
    }

    private fun meanVolume(video: Path): Double {
        val out =
            processRunner
                .run(
                    listOf(
                        "ffmpeg",
                        "-hide_banner",
                        "-nostats",
                        "-i",
                        video.toString(),
                        "-map",
                        "0:a",
                        "-af",
                        "volumedetect",
                        "-f",
                        "null",
                        "-",
                    ),
                ).stderr
        return Regex("mean_volume: (-?[\\d.]+) dB")
            .find(out)
            ?.groupValues
            ?.get(1)
            ?.toDouble() ?: fail("no volumedetect output")
    }

    private fun frame(
        video: Path,
        atSeconds: Double,
    ): BufferedImage {
        val png = Files.createTempFile(downloads, "frame-", ".png")
        ffmpeg.run("-ss", atSeconds.toString(), "-i", video.toString(), "-frames:v", "1", "-update", "1", png.toString())
        return ImageIO.read(png.toFile())
    }

    /** First row (from the top) containing bright text pixels on the dark background. */
    private fun topInkRow(image: BufferedImage): Int? =
        (0 until image.height).firstOrNull { y ->
            (0 until image.width).any { x ->
                val rgb = image.getRGB(x, y)
                ((rgb shr 16 and 0xFF) + (rgb shr 8 and 0xFF) + (rgb and 0xFF)) / 3 > 160
            }
        }
}
