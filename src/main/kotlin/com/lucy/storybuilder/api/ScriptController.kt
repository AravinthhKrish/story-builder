package com.lucy.storybuilder.api

import com.lucy.storybuilder.api.dto.CreateScriptRequest
import com.lucy.storybuilder.api.dto.JobCreatedResponse
import com.lucy.storybuilder.api.dto.RenderScriptRequest
import com.lucy.storybuilder.api.dto.SkillView
import com.lucy.storybuilder.api.dto.UpdateScriptRequest
import com.lucy.storybuilder.config.StoryBuilderProperties
import com.lucy.storybuilder.job.JobRunner
import com.lucy.storybuilder.script.ScriptCharacter
import com.lucy.storybuilder.script.ScriptDirector
import com.lucy.storybuilder.script.ScriptScene
import com.lucy.storybuilder.script.ScriptStore
import com.lucy.storybuilder.script.StoryScript
import com.lucy.storybuilder.skills.SkillRegistry
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.util.UUID

/**
 * Staged API: story → scene script (review / edit) → render. The script is what the director wrote:
 * narration, characters and one picture description per scene, all editable before any image or
 * voice is generated.
 */
@RestController
@RequestMapping("/api/v1")
class ScriptController(
    private val props: StoryBuilderProperties,
    private val skills: SkillRegistry,
    private val director: ScriptDirector,
    private val scripts: ScriptStore,
    private val specFactory: JobSpecFactory,
    private val runner: JobRunner,
) {
    @GetMapping("/skills")
    fun listSkills(): List<SkillView> = skills.all().map { SkillView.of(it, props.defaultSkill) }

    /** Runs the director synchronously (bounded by `storybuilder.llm.timeout`) and returns the script. */
    @PostMapping("/scripts")
    fun createScript(
        @Valid @RequestBody request: CreateScriptRequest,
    ): ResponseEntity<StoryScript> {
        val skill = specFactory.skill(request.skill)
        val script = scripts.save(director.direct(specFactory.checkText(request.text), skill))
        return ResponseEntity.created(URI.create(apiUrl("/api/v1/scripts/{id}", script.id))).body(script)
    }

    @GetMapping("/scripts/{id}")
    fun getScript(
        @PathVariable id: UUID,
    ): StoryScript = scripts.get(id) ?: throw ScriptNotFoundException(id)

    @PutMapping("/scripts/{id}")
    fun updateScript(
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateScriptRequest,
    ): StoryScript {
        val current = getScript(id)
        val skill = specFactory.skill(current.skill)
        val characters =
            request.characters?.map { ScriptCharacter(it.id!!, it.name!!.trim(), it.look!!.trim()) } ?: current.characters
        val known = characters.map { it.id }.toSet()
        val scenes =
            request.scenes?.mapIndexed { i, s ->
                val prompt = s.imagePrompt.orEmpty().trim()
                // Sending back the prompt we generated (e.g. after a GET) is not a hand-written prompt.
                val generatedBefore =
                    current.scenes
                        .getOrNull(i)
                        ?.takeIf { !it.imagePromptCustom }
                        ?.imagePrompt
                ScriptScene(
                    narration = s.narration!!.trim(),
                    setting = s.setting.orEmpty().trim(),
                    action = s.action.orEmpty().trim(),
                    emotion = s.emotion.orEmpty().trim(),
                    camera = s.camera ?: skill.cameraMoves[i % skill.cameraMoves.size],
                    characters = s.characters.orEmpty().filter { it in known },
                    imagePrompt = prompt,
                    imagePromptCustom = prompt.isNotEmpty() && prompt != generatedBefore,
                )
            } ?: current.scenes.map { it.copy(characters = it.characters.filter { c -> c in known }) }
        // Generated prompts are rebuilt below from the new characters/scenes; hand-written ones are kept.

        val updated =
            current.copy(
                title = request.title?.trim() ?: current.title,
                seed = request.seed ?: current.seed,
                characters = characters,
                scenes = scenes,
                updatedAt = Instant.now(),
            )
        return scripts.save(director.withImagePrompts(updated, skill))
    }

    @PostMapping("/scripts/{id}/render")
    fun renderScript(
        @PathVariable id: UUID,
        @Valid @RequestBody(required = false) request: RenderScriptRequest?,
    ): ResponseEntity<JobCreatedResponse> = accepted(runner.submit(specFactory.forScript(getScript(id), request?.options)))
}
