package com.lucy.storybuilder

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(properties = ["storybuilder.verify-toolchain=false", "storybuilder.tts.engine=silent"])
class StoryBuilderApplicationTests {
    @Test
    fun contextLoads() {
    }
}
