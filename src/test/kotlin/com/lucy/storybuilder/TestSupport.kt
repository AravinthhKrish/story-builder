package com.lucy.storybuilder

import com.lucy.storybuilder.ai.llm.LlmClient
import com.lucy.storybuilder.config.StoryBuilderProperties
import com.lucy.storybuilder.pipeline.assets.tts.SpeechRequest
import com.lucy.storybuilder.pipeline.assets.tts.TtsEngine
import com.lucy.storybuilder.skills.SkillRegistry

/** A TTS engine stand-in for tests that never synthesize (spec building, director). */
class NoopTts(
    override val name: String = "piper",
) : TtsEngine {
    override fun synthesize(request: SpeechRequest) = throw UnsupportedOperationException("not used in this test")

    override fun checkAvailable() = Unit
}

/** An LLM that returns a canned reply (or throws), recording what it was asked. */
class FakeLlm(
    private val reply: () -> String,
) : LlmClient {
    var lastSystem: String? = null
    var lastSchema: Map<String, Any>? = null
    override val description = "fake-llm"

    override fun chatJson(
        system: String,
        user: String,
        schema: Map<String, Any>,
    ): String {
        lastSystem = system
        lastSchema = schema
        return reply()
    }

    override fun problem(): String? = null
}

fun bundledSkills(props: StoryBuilderProperties = StoryBuilderProperties()) = SkillRegistry(props)

/** A well-formed director reply for a two-character, three-scene story. */
val CANNED_SCRIPT_JSON =
    """
    {
      "title": "The Fox and the Owl",
      "characters": [
        {"id": "pip", "name": "Pip", "look": "small orange fox with a green scarf and white-tipped tail"},
        {"id": "olly", "name": "Olly", "look": "round grey owl with big amber eyes and tiny glasses"}
      ],
      "scenes": [
        {"narration": "Pip the fox woke before dawn, eager for adventure.", "setting": "cosy den under an oak, sunrise",
         "action": "Pip stretches at the den entrance", "emotion": "excited", "camera": "zoom_in", "characters": ["pip"]},
        {"narration": "Olly the owl watched from a branch and hooted hello.", "setting": "misty forest, early morning",
         "action": "Olly waves a wing at Pip below", "emotion": "friendly", "camera": "pan-right", "characters": ["olly", "pip", "ghost"]},
        {"narration": "Together they raced home as the sun rose.", "setting": "golden meadow, morning sun",
         "action": "Pip runs while Olly glides above", "emotion": "joyful", "camera": "sideways", "characters": ["pip", "olly"]}
      ]
    }
    """.trimIndent()
