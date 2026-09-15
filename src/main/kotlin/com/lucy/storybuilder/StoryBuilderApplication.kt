package com.lucy.storybuilder

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class StoryBuilderApplication

fun main(args: Array<String>) {
    // Frames are drawn off-screen with Java2D; never try to open a window (or a Dock icon on macOS).
    System.setProperty("java.awt.headless", "true")
    runApplication<StoryBuilderApplication>(*args)
}
