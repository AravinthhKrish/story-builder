package com.lucy.storybuilder.ai.llm

import com.lucy.storybuilder.ai.AiHttp
import com.lucy.storybuilder.config.StoryBuilderProperties
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import tools.jackson.databind.JsonNode
import java.util.Locale

/** A chat model that answers with JSON constrained by a JSON schema. Used by the scene director. */
interface LlmClient {
    /** e.g. "ollama llama3.2:3b" — for logs and job notes. */
    val description: String

    /** Returns the model's JSON reply as text. Throws on transport errors, timeouts and HTTP errors. */
    fun chatJson(
        system: String,
        user: String,
        schema: Map<String, Any>,
    ): String

    /** Null when the model is reachable and installed, otherwise a human-readable problem. Never throws. */
    fun problem(): String?
}

/**
 * Ollama's native chat API. `format` takes a JSON schema, so the reply is grammar-constrained to
 * valid JSON of the right shape — far more reliable with small local models than prompting alone.
 */
class OllamaLlmClient(
    private val config: StoryBuilderProperties.Llm,
) : LlmClient {
    private val client = AiHttp.client(config.baseUrl, config.timeout)
    private val log = LoggerFactory.getLogger(javaClass)
    override val description = "ollama ${config.model}"

    override fun chatJson(
        system: String,
        user: String,
        schema: Map<String, Any>,
    ): String {
        val body =
            mapOf(
                "model" to config.model,
                "stream" to false,
                "format" to schema,
                "keep_alive" to config.keepAlive,
                "options" to mapOf("temperature" to config.temperature, "num_predict" to config.maxOutputTokens),
                "messages" to
                    listOf(
                        mapOf("role" to "system", "content" to system),
                        mapOf("role" to "user", "content" to user),
                    ),
            )
        val reply =
            client
                .post()
                .uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode::class.java)
        reply?.let { logTimings(it) }
        return reply
            ?.path("message")
            ?.path("content")
            ?.asString()
            .orEmpty()
    }

    /** Ollama reports where the time went (nanoseconds); the director's latency budget depends on it. */
    private fun logTimings(reply: JsonNode) {
        fun seconds(field: String) = reply.path(field).asLong(0) / 1e9

        fun fmt(value: Double) = String.format(Locale.ROOT, "%.1f", value)
        val outTokens = reply.path("eval_count").asLong(0)
        val outSeconds = seconds("eval_duration")
        log.info(
            "ollama {}: load {}s, prompt {} tok in {}s, output {} tok in {}s ({} tok/s)",
            config.model,
            fmt(seconds("load_duration")),
            reply.path("prompt_eval_count").asLong(0),
            fmt(seconds("prompt_eval_duration")),
            outTokens,
            fmt(outSeconds),
            if (outSeconds > 0) fmt(outTokens / outSeconds) else "-",
        )
    }

    override fun problem(): String? =
        try {
            val tags =
                client
                    .get()
                    .uri("/api/tags")
                    .retrieve()
                    .body(JsonNode::class.java)
            val installed: List<String> =
                tags
                    ?.path("models")
                    ?.values()
                    ?.map { it.path("name").asString() }
                    .orEmpty()
            val wanted = if (':' in config.model) config.model else "${config.model}:latest"
            if (installed.none { it == wanted }) {
                "model ${config.model} is not pulled on ${config.baseUrl} (run: ollama pull ${config.model})"
            } else {
                null
            }
        } catch (e: Exception) {
            "Ollama is not reachable at ${config.baseUrl} (${e.javaClass.simpleName})"
        }
}

/** Any OpenAI-compatible `/chat/completions` endpoint (vLLM, LM Studio, llama.cpp server, OpenAI, …). */
class OpenAiCompatibleLlmClient(
    private val config: StoryBuilderProperties.Llm,
) : LlmClient {
    private val client = AiHttp.client(config.baseUrl, config.timeout)
    override val description = "openai-compatible ${config.model}"

    override fun chatJson(
        system: String,
        user: String,
        schema: Map<String, Any>,
    ): String {
        val body =
            mapOf(
                "model" to config.model,
                "temperature" to config.temperature,
                "max_tokens" to config.maxOutputTokens,
                "response_format" to
                    mapOf(
                        "type" to "json_schema",
                        "json_schema" to mapOf("name" to "story_script", "schema" to schema),
                    ),
                "messages" to
                    listOf(
                        mapOf("role" to "system", "content" to system),
                        mapOf("role" to "user", "content" to user),
                    ),
            )
        val reply =
            client
                .post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .headers { h -> config.apiKey?.takeIf { it.isNotBlank() }?.let { h.setBearerAuth(it) } }
                .body(body)
                .retrieve()
                .body(JsonNode::class.java)
        return reply
            ?.path("choices")
            ?.path(0)
            ?.path("message")
            ?.path("content")
            ?.asString()
            .orEmpty()
    }

    override fun problem(): String? = null
}
