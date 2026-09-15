package com.lucy.storybuilder.ai

import com.lucy.storybuilder.ai.image.FalImageProvider
import com.lucy.storybuilder.ai.image.ImageProvider
import com.lucy.storybuilder.ai.image.PlaceholderImageProvider
import com.lucy.storybuilder.ai.llm.LlmClient
import com.lucy.storybuilder.ai.llm.OllamaLlmClient
import com.lucy.storybuilder.ai.llm.OpenAiCompatibleLlmClient
import com.lucy.storybuilder.config.StoryBuilderProperties
import com.lucy.storybuilder.config.StoryBuilderProperties.ImageProviderType
import com.lucy.storybuilder.config.StoryBuilderProperties.LlmProvider
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/** Wires the configured director LLM and image provider (`storybuilder.llm.*`, `storybuilder.images.*`). */
@Configuration
class AiConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun llmClient(props: StoryBuilderProperties): LlmClient =
        when (props.llm.provider) {
            LlmProvider.OLLAMA -> OllamaLlmClient(props.llm)
            LlmProvider.OPENAI_COMPATIBLE -> OpenAiCompatibleLlmClient(props.llm)
        }

    @Bean
    fun placeholderImageProvider(): PlaceholderImageProvider = PlaceholderImageProvider()

    /** Primary: the placeholder bean is also an [ImageProvider], kept separately as the failure fallback. */
    @Bean
    @Primary
    fun imageProvider(
        props: StoryBuilderProperties,
        placeholder: PlaceholderImageProvider,
    ): ImageProvider {
        val hasFalKey =
            !props.images.fal.apiKey
                .isNullOrBlank()
        return when (props.images.provider) {
            ImageProviderType.FAL -> FalImageProvider(props.images.fal, props.images.timeout)
            ImageProviderType.PLACEHOLDER -> placeholder
            ImageProviderType.AUTO ->
                if (hasFalKey) {
                    FalImageProvider(props.images.fal, props.images.timeout)
                } else {
                    log.warn("No FAL_KEY configured: illustrated skills will use offline placeholder art")
                    placeholder
                }
        }.also { log.info("Image provider: {}", it.description) }
    }
}
