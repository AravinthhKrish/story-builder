package com.lucy.storybuilder.script

import com.lucy.storybuilder.ai.AiHttp
import com.lucy.storybuilder.ai.llm.LlmClient
import com.lucy.storybuilder.pipeline.breakdown.StoryBreakdownService
import com.lucy.storybuilder.pipeline.timeline.CameraMove
import com.lucy.storybuilder.skills.PromptTemplate
import com.lucy.storybuilder.skills.Skill
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.text.BreakIterator
import java.time.Duration
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.random.Random

/**
 * Stage 1 for illustrated skills: turns a story into a [StoryScript] — condensed narration, a
 * character sheet and one picture description per scene — using the configured LLM and the skill's
 * pre-filled `director.md`. If the model is unreachable, slow, or returns junk, a rule-based script
 * is built instead (marked `degraded`), so the job still meets its deadline.
 */
@Service
class ScriptDirector(
    private val llm: LlmClient,
    private val breakdown: StoryBreakdownService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = JsonMapper.builder().build()
    private val calls = Executors.newVirtualThreadPerTaskExecutor()

    /**
     * [budget] caps how long the LLM may take (one-shot jobs pass their share of the delivery
     * target); past it the call is cancelled and the rule-based script is used. Null = no cap
     * beyond `storybuilder.llm.timeout`.
     */
    fun direct(
        text: String,
        skill: Skill,
        budget: Duration? = null,
    ): StoryScript {
        val seed = Random.nextLong(1, Int.MAX_VALUE.toLong())
        if (!skill.isIllustrated) return plainScript(text, skill, seed)

        val started = System.nanoTime()
        return try {
            fromLlm(text, skill, seed, budget).also {
                log.info(
                    "Director ({}) wrote {} scenes in {} ms",
                    llm.description,
                    it.scenes.size,
                    (System.nanoTime() - started) / 1_000_000,
                )
            }
        } catch (e: Exception) {
            val reason = if (e is DirectorOutputException) "${llm.description} ${e.message}" else AiHttp.describe(llm.description, e)
            log.warn("Director fell back to rule-based script: {}", reason)
            fallback(text, skill, seed, "Rule-based scenes used: $reason")
        }
    }

    private fun callLlm(
        system: String,
        user: String,
        schema: Map<String, Any>,
        budget: Duration?,
    ): String {
        if (budget == null) return llm.chatJson(system, user, schema)
        val call = calls.submit<String> { llm.chatJson(system, user, schema) }
        return try {
            call.get(budget.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            call.cancel(true) // interrupts the HTTP request so the model stops generating
            throw DirectorOutputException("took longer than its ${budget.toMillis() / 1000.0} s share of the delivery budget")
        } catch (e: ExecutionException) {
            throw (e.cause as? Exception) ?: e
        }
    }

    /** (Re)builds image prompts from the skill template; explicit prompts on scenes are kept. */
    fun withImagePrompts(
        script: StoryScript,
        skill: Skill,
    ): StoryScript {
        if (!skill.isIllustrated) return script
        val byId = script.characters.associateBy { it.id }
        return script.copy(
            scenes =
                script.scenes.map { scene ->
                    if (scene.imagePrompt.isNotBlank()) return@map scene
                    val cast =
                        scene.characters
                            .mapNotNull { byId[it] }
                            .joinToString(" and ") { "${it.name} (${it.look})" }
                    val prompt =
                        PromptTemplate.fill(
                            skill.imageTemplate,
                            mapOf(
                                "style" to skill.style,
                                "title" to script.title,
                                "setting" to
                                    clause(
                                        scene.setting.ifBlank {
                                            scene.narration
                                                .words()
                                                .take(12)
                                                .joinToString(" ")
                                        },
                                    ),
                                "action" to clause(scene.action),
                                "emotion" to clause(scene.emotion.ifBlank { "calm" }).lowercase(),
                                "characters" to cast,
                            ),
                        )
                    scene.copy(imagePrompt = tidy(prompt))
                },
        )
    }

    private fun fromLlm(
        text: String,
        skill: Skill,
        seed: Long,
        budget: Duration?,
    ): StoryScript {
        val system =
            PromptTemplate.fill(
                skill.directorPrompt,
                mapOf(
                    "style_name" to skill.name.replace('-', ' '),
                    "style" to skill.style,
                    "min_scenes" to skill.scenes.min.toString(),
                    "max_scenes" to skill.scenes.max.toString(),
                    "max_words" to skill.maxWords.toString(),
                    "camera_moves" to skill.cameraMoves.joinToString(", ") { it.json() },
                ),
            )
        val reply = callLlm(system, "Story:\n$text", schema(skill), budget)
        val root =
            runCatching { json.readTree(reply) }.getOrNull()
                ?: throw DirectorOutputException("returned invalid JSON")
        return withImagePrompts(parse(root, skill, seed), skill)
    }

    private fun parse(
        root: JsonNode,
        skill: Skill,
        seed: Long,
    ): StoryScript {
        val notes = mutableListOf<String>()
        val characters =
            root
                .path("characters")
                .mapNotNull { node ->
                    val name = node.text("name").ifBlank { return@mapNotNull null }
                    val id =
                        node
                            .text("id")
                            .ifBlank { name }
                            .lowercase()
                            .replace(Regex("[^a-z0-9_-]+"), "_")
                    ScriptCharacter(
                        id,
                        name,
                        node
                            .text("look")
                            .words()
                            .take(40)
                            .joinToString(" "),
                    )
                }.distinctBy { it.id }
        val known = characters.map { it.id }.toSet()

        var scenes =
            root.path("scenes").mapIndexedNotNull { i, node ->
                val narration = node.text("narration").ifBlank { return@mapIndexedNotNull null }
                ScriptScene(
                    narration = narration,
                    setting = node.text("setting"),
                    action = node.text("action"),
                    emotion = node.text("emotion"),
                    camera = CameraMove.parseOrNull(node.text("camera")) ?: skill.cameraMoves[i % skill.cameraMoves.size],
                    characters =
                        node
                            .path("characters")
                            .values()
                            .map { it.asString().lowercase() }
                            .filter { it in known },
                )
            }
        if (scenes.isEmpty()) throw DirectorOutputException("returned no usable scenes")
        if (scenes.size > skill.scenes.max) {
            notes += "Director returned ${scenes.size} scenes; kept the first ${skill.scenes.max}"
            scenes = scenes.take(skill.scenes.max)
        }
        val words = scenes.sumOf { it.narration.words().size }
        if (words > skill.maxWords) notes += "Narration is $words words (budget ${skill.maxWords}); audio will be fitted to the time limit"

        return StoryScript(
            id = UUID.randomUUID(),
            skill = skill.name,
            title =
                root.text("title").ifBlank {
                    scenes
                        .first()
                        .narration
                        .words()
                        .take(6)
                        .joinToString(" ")
                },
            seed = seed,
            characters = characters,
            scenes = scenes,
            notes = notes,
        )
    }

    /** LLM-free script for illustrated skills: story split into scenes, trimmed to the word budget. */
    fun fallback(
        text: String,
        skill: Skill,
        seed: Long,
        note: String,
    ): StoryScript {
        val trimmed = trimToWords(text, skill.maxWords)
        val parts = breakdown.breakdown(trimmed).take(skill.scenes.max)
        check(parts.isNotEmpty()) { "Story contains no text" }
        val scenes =
            parts.mapIndexed { i, part ->
                ScriptScene(
                    narration = part.text,
                    setting =
                        part.text
                            .words()
                            .take(14)
                            .joinToString(" "),
                    emotion = "calm",
                    camera = skill.cameraMoves[i % skill.cameraMoves.size],
                )
            }
        val script =
            StoryScript(
                id = UUID.randomUUID(),
                skill = skill.name,
                title =
                    parts
                        .first()
                        .text
                        .words()
                        .take(6)
                        .joinToString(" "),
                seed = seed,
                characters = emptyList(),
                scenes = scenes,
                degraded = true,
                notes = listOfNotNull(note, "Story trimmed to ${skill.maxWords} words".takeIf { trimmed.length < text.trim().length }),
            )
        return withImagePrompts(script, skill)
    }

    /** text-scroll: every paragraph as-is, no AI. */
    private fun plainScript(
        text: String,
        skill: Skill,
        seed: Long,
    ): StoryScript {
        val parts = breakdown.breakdown(text)
        check(parts.isNotEmpty()) { "Story contains no text" }
        return StoryScript(
            id = UUID.randomUUID(),
            skill = skill.name,
            title =
                parts
                    .first()
                    .text
                    .words()
                    .take(6)
                    .joinToString(" "),
            seed = seed,
            characters = emptyList(),
            scenes = parts.map { ScriptScene(narration = it.text) },
        )
    }

    private fun schema(skill: Skill): Map<String, Any> {
        fun str() = mapOf("type" to "string")
        return mapOf(
            "type" to "object",
            "required" to listOf("title", "characters", "scenes"),
            "properties" to
                mapOf(
                    "title" to str(),
                    "characters" to
                        mapOf(
                            "type" to "array",
                            "items" to
                                mapOf(
                                    "type" to "object",
                                    "required" to listOf("id", "name", "look"),
                                    "properties" to mapOf("id" to str(), "name" to str(), "look" to str()),
                                ),
                        ),
                    "scenes" to
                        mapOf(
                            "type" to "array",
                            "minItems" to skill.scenes.min,
                            "maxItems" to skill.scenes.max,
                            "items" to
                                mapOf(
                                    "type" to "object",
                                    "required" to listOf("narration", "setting", "action", "emotion", "camera", "characters"),
                                    "properties" to
                                        mapOf(
                                            "narration" to str(),
                                            "setting" to str(),
                                            "action" to str(),
                                            "emotion" to str(),
                                            "camera" to mapOf("type" to "string", "enum" to skill.cameraMoves.map { it.json() }),
                                            "characters" to mapOf("type" to "array", "items" to str()),
                                        ),
                                ),
                        ),
                ),
        )
    }

    private fun trimToWords(
        text: String,
        maxWords: Int,
    ): String {
        if (text.words().size <= maxWords) return text.trim()
        val sentences = BreakIterator.getSentenceInstance(Locale.ENGLISH).apply { setText(text) }
        var end = 0
        var count = 0
        var next = sentences.next()
        while (next != BreakIterator.DONE) {
            val n = text.substring(end, next).words().size
            if (count + n > maxWords && count > 0) break
            count += n
            end = next
            next = sentences.next()
        }
        return text.substring(0, end).trim().ifBlank { text.words().take(maxWords).joinToString(" ") }
    }

    private fun JsonNode.text(field: String): String = path(field).asString("").trim()

    private fun String.words(): List<String> = split(Regex("\\s+")).filter { it.isNotBlank() }

    /** Director fields are slotted mid-sentence into the image template, so drop their end punctuation. */
    private fun clause(text: String): String = text.trim().trimEnd('.', '!', '?', ';', ',', ':').trim()

    private fun tidy(prompt: String): String =
        prompt
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\s+([.,])"), "$1")
            .replace(Regex("[.,]*\\.[.,]*"), ".")
            .replace(Regex(",{2,}"), ",")
            .trim()
}

class DirectorOutputException(
    message: String,
) : RuntimeException(message)
