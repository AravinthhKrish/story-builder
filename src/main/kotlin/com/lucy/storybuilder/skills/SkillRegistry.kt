package com.lucy.storybuilder.skills

import com.lucy.storybuilder.config.StoryBuilderProperties
import org.slf4j.LoggerFactory
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Component
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

class UnknownSkillException(
    val skill: String,
    val available: Collection<String>,
) : RuntimeException("Unknown skill '$skill'; available: ${available.sorted()}")

/**
 * Loads style skills once at startup: the bundled `classpath:skills/<name>/`, then any folders in
 * `storybuilder.skills.dir` (which override bundled ones of the same name). Every skill is validated
 * up front — including a dry run of its templates — so a broken skill stops startup instead of a job.
 */
@Component
class SkillRegistry(
    props: StoryBuilderProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val yaml = YAMLMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val skills: Map<String, Skill>

    init {
        val bundled =
            PathMatchingResourcePatternResolver()
                .getResources("classpath*:skills/*/skill.yaml")
                .map { load(it) }
        val external =
            props.skills.dir
                ?.takeIf { it.isDirectory() }
                ?.listDirectoryEntries()
                ?.filter { it.resolve("skill.yaml").exists() }
                ?.map { load(FileSystemResource(it.resolve("skill.yaml"))) }
                .orEmpty()
        skills = (bundled + external).associateBy { it.name }
        check(props.defaultSkill in skills) { "Default skill '${props.defaultSkill}' not found; available: ${skills.keys.sorted()}" }
        log.info("Loaded {} skills: {} (default: {})", skills.size, skills.keys.sorted(), props.defaultSkill)
    }

    fun all(): List<Skill> = skills.values.sortedBy { it.name }

    fun get(name: String): Skill = skills[name] ?: throw UnknownSkillException(name, skills.keys)

    private fun load(manifest: Resource): Skill {
        val where = manifest.description
        try {
            val base: Skill = manifest.inputStream.use { yaml.readValue(it) }
            val skill =
                base.copy(
                    directorPrompt = manifest.sibling("director.md").orEmpty(),
                    imageTemplate = manifest.sibling("image.md").orEmpty(),
                )
            validate(skill)
            return skill
        } catch (e: Exception) {
            throw IllegalStateException("Invalid skill at $where: ${e.message}", e)
        }
    }

    private fun Resource.sibling(name: String): String? =
        createRelative(name).takeIf { it.exists() }?.inputStream?.use { String(it.readAllBytes()) }

    private fun validate(skill: Skill) {
        require(skill.name.matches(Regex("[a-z0-9][a-z0-9-]{0,40}"))) { "name must be lowercase letters, digits and dashes" }
        with(skill.format) {
            require(width % 2 == 0 && height % 2 == 0 && width in 16..3840 && height in 16..3840) { "format must be even, 16..3840" }
            require(fps in 1..60) { "fps must be 1..60" }
            require(crf in 0..51) { "crf must be 0..51" }
        }
        if (!skill.isIllustrated) return

        require(skill.style.isNotBlank()) { "illustrated skills need a style" }
        require(skill.cameraMoves.isNotEmpty()) { "cameraMoves must not be empty" }
        require(skill.scenes.min in 1..skill.scenes.max && skill.scenes.max <= 12) { "scenes must satisfy 1 <= min <= max <= 12" }
        require(skill.directorPrompt.isNotBlank()) { "director.md is missing or empty" }
        require(skill.imageTemplate.isNotBlank()) { "image.md is missing or empty" }
        PromptTemplate.fill(skill.directorPrompt, DIRECTOR_PLACEHOLDERS.associateWith { "x" })
        PromptTemplate.fill(skill.imageTemplate, IMAGE_PLACEHOLDERS.associateWith { "x" })
    }

    companion object {
        val DIRECTOR_PLACEHOLDERS = setOf("style_name", "style", "min_scenes", "max_scenes", "max_words", "camera_moves")
        val IMAGE_PLACEHOLDERS = setOf("style", "title", "setting", "action", "emotion", "characters")
    }
}
