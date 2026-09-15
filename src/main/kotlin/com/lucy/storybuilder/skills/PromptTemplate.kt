package com.lucy.storybuilder.skills

/** Minimal `{{placeholder}}` substitution for skill prompts; no template engine, no logic in templates. */
object PromptTemplate {
    private val PLACEHOLDER = Regex("""\{\{\s*([A-Za-z0-9_]+)\s*}}""")

    fun placeholders(template: String): Set<String> = PLACEHOLDER.findAll(template).map { it.groupValues[1] }.toSet()

    /** Fills every placeholder; an unknown one is an error so a typo in a skill fails at startup, not mid-render. */
    fun fill(
        template: String,
        values: Map<String, String>,
    ): String =
        PLACEHOLDER
            .replace(template) { match ->
                val key = match.groupValues[1]
                values[key] ?: throw IllegalArgumentException("Unknown placeholder {{$key}}; available: ${values.keys.sorted()}")
            }.trim()
}
