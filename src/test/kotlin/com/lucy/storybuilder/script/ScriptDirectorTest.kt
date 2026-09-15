package com.lucy.storybuilder.script

import com.lucy.storybuilder.CANNED_SCRIPT_JSON
import com.lucy.storybuilder.FakeLlm
import com.lucy.storybuilder.bundledSkills
import com.lucy.storybuilder.config.StoryBuilderProperties
import com.lucy.storybuilder.pipeline.breakdown.StoryBreakdownService
import com.lucy.storybuilder.pipeline.timeline.CameraMove
import org.junit.jupiter.api.Test
import java.net.SocketTimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScriptDirectorTest {
    private val props = StoryBuilderProperties()
    private val skills = bundledSkills(props)
    private val cartoon = skills.get("cartoon-storybook")
    private val story = "Pip the fox woke early. Olly the owl said hello.\n\nThey raced home together as the sun rose."

    private fun director(llm: FakeLlm) = ScriptDirector(llm, StoryBreakdownService(props))

    @Test
    fun `llm reply becomes a validated script with consistent image prompts`() {
        val llm = FakeLlm { CANNED_SCRIPT_JSON }
        val script = director(llm).direct(story, cartoon)

        assertFalse(script.degraded)
        assertEquals("The Fox and the Owl", script.title)
        assertEquals(listOf("pip", "olly"), script.characters.map { it.id })
        assertEquals(3, script.scenes.size)
        assertEquals(listOf(CameraMove.ZOOM_IN, CameraMove.PAN_RIGHT), script.scenes.take(2).map { it.camera })
        assertEquals(cartoon.cameraMoves[2], script.scenes[2].camera, "invalid camera falls back to the skill's rotation")
        assertEquals(listOf("olly", "pip"), script.scenes[1].characters, "unknown character ids are dropped")

        val prompt = script.scenes[1].imagePrompt
        assertTrue(prompt.startsWith("Bright 2D cartoon"), prompt)
        assertTrue("Olly (round grey owl with big amber eyes and tiny glasses)" in prompt, "character look reused: $prompt")
        assertTrue("misty forest" in prompt && "no text" in prompt, prompt)
        assertFalse("{{" in prompt)

        // The skill's pre-filled director prompt was sent with its placeholders filled.
        assertTrue("between 3 and 6 scenes" in llm.lastSystem!!)
        assertTrue("At most 130 words" in llm.lastSystem!!)
        assertFalse("{{" in llm.lastSystem!!)
    }

    @Test
    fun `llm failure falls back to a rule-based script`() {
        val script = director(FakeLlm { throw SocketTimeoutException("Read timed out") }).direct(story, cartoon)

        assertTrue(script.degraded)
        assertEquals(2, script.scenes.size)
        assertTrue(script.scenes.all { it.imagePrompt.startsWith("Bright 2D cartoon") })
        assertTrue(script.notes.single().contains("SocketTimeoutException"), script.notes.toString())
    }

    @Test
    fun `invalid json and empty scenes also fall back`() {
        assertTrue(director(FakeLlm { "not json" }).direct(story, cartoon).degraded)
        assertTrue(director(FakeLlm { """{"title":"x","characters":[],"scenes":[]}""" }).direct(story, cartoon).degraded)
    }

    @Test
    fun `fallback trims long stories to the word budget on a sentence boundary`() {
        val long = (1..60).joinToString(" ") { "Sentence number $it is here." } // 300 words
        val script = director(FakeLlm { throw RuntimeException("down") }).direct(long, cartoon)

        val words = script.scenes.sumOf { it.narration.split(" ").size }
        assertTrue(words <= cartoon.maxWords, "words $words")
        assertTrue(
            script.scenes
                .last()
                .narration
                .endsWith("."),
        )
        assertTrue(script.notes.any { it.contains("trimmed") })
    }

    @Test
    fun `text-scroll needs no llm and keeps every paragraph`() {
        val llm = FakeLlm { error("must not be called") }
        val script = director(llm).direct(story, skills.get("text-scroll"))
        assertEquals(2, script.scenes.size)
        assertEquals(null, llm.lastSystem)
    }

    @Test
    fun `hand-written image prompts survive a rebuild, generated ones are regenerated`() {
        val d = director(FakeLlm { CANNED_SCRIPT_JSON })
        val script = d.direct(story, cartoon)
        assertFalse(script.scenes.any { it.imagePromptCustom })

        val edited =
            script.copy(
                scenes =
                    script.scenes.mapIndexed { i, s ->
                        if (i == 0) s.copy(imagePrompt = "custom", imagePromptCustom = true) else s.copy(imagePrompt = "")
                    },
            )
        val rebuilt = d.withImagePrompts(edited, cartoon)
        assertEquals("custom", rebuilt.scenes[0].imagePrompt)
        assertEquals(script.scenes[1].imagePrompt, rebuilt.scenes[1].imagePrompt)
    }

    @Test
    fun `stale generated prompts pick up edited character looks`() {
        val d = director(FakeLlm { CANNED_SCRIPT_JSON })
        val script = d.direct(story, cartoon)
        val recast = script.copy(characters = script.characters.map { if (it.id == "olly") it.copy(look = "tiny white snowy owl") else it })

        val rebuilt = d.withImagePrompts(recast, cartoon)

        assertTrue("Olly (tiny white snowy owl)" in rebuilt.scenes[1].imagePrompt, rebuilt.scenes[1].imagePrompt)
        assertFalse("round grey owl" in rebuilt.scenes[1].imagePrompt)
    }
}
