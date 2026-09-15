package com.lucy.storybuilder.skills

import com.lucy.storybuilder.config.StoryBuilderProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillRegistryTest {
    @Test
    fun `bundled skills load with their prompts`() {
        val registry = SkillRegistry(StoryBuilderProperties())

        assertEquals(
            listOf("anime", "cartoon-storybook", "comic-book", "text-scroll", "watercolor-bedtime"),
            registry.all().map { it.name },
        )
        val cartoon = registry.get("cartoon-storybook")
        assertEquals(RendererType.ILLUSTRATED, cartoon.renderer)
        assertEquals(720 to 1280, cartoon.format.width to cartoon.format.height)
        assertTrue("{{max_words}}" in cartoon.directorPrompt)
        assertTrue("{{characters}}" in cartoon.imageTemplate)
        assertEquals("en_US-lessac-medium", cartoon.voices["piper"])
        assertEquals(RendererType.TEXT_SCROLL, registry.get("text-scroll").renderer)
    }

    @Test
    fun `unknown skill names the available ones`() {
        val e = assertThrows<UnknownSkillException> { SkillRegistry(StoryBuilderProperties()).get("nope") }
        assertTrue("cartoon-storybook" in e.message!!)
    }

    @Test
    fun `external skill folder adds and overrides skills`(
        @TempDir dir: Path,
    ) {
        skill(dir, "anime", style = "overridden style")
        skill(dir, "pixel-art", style = "16-bit pixel art")

        val registry = SkillRegistry(StoryBuilderProperties(skills = StoryBuilderProperties.Skills(dir)))

        assertEquals("overridden style", registry.get("anime").style)
        assertEquals("16-bit pixel art", registry.get("pixel-art").style)
    }

    @Test
    fun `a skill with an unknown placeholder fails startup`(
        @TempDir dir: Path,
    ) {
        skill(dir, "broken", imageTemplate = "{{style}} with {{colour}}")
        val e = assertThrows<IllegalStateException> { SkillRegistry(StoryBuilderProperties(skills = StoryBuilderProperties.Skills(dir))) }
        assertTrue("colour" in e.message!!, e.message)
    }

    @Test
    fun `missing default skill fails startup`() {
        assertThrows<IllegalStateException> { SkillRegistry(StoryBuilderProperties(defaultSkill = "missing")) }
    }

    @Test
    fun `template fill replaces every placeholder and rejects unknown ones`() {
        assertEquals("a b", PromptTemplate.fill("{{x}} {{ y }}", mapOf("x" to "a", "y" to "b")))
        assertEquals(setOf("x", "y"), PromptTemplate.placeholders("{{x}} {{ y }} {{x}}"))
        assertThrows<IllegalArgumentException> { PromptTemplate.fill("{{z}}", mapOf("x" to "a")) }
    }

    private fun skill(
        root: Path,
        name: String,
        style: String = "flat style",
        imageTemplate: String = "{{style}}. {{setting}}. {{characters}} {{action}}, {{emotion}}.",
    ) {
        val dir = root.resolve(name).createDirectories()
        dir.resolve("skill.yaml").writeText(
            """
            name: $name
            description: test skill
            renderer: illustrated
            style: "$style"
            """.trimIndent(),
        )
        dir.resolve("director.md").writeText("Write {{min_scenes}}-{{max_scenes}} scenes, {{max_words}} words, {{camera_moves}}.")
        dir.resolve("image.md").writeText(imageTemplate)
    }
}
