import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

group = "com.lucy"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("tools.jackson.dataformat:jackson-dataformat-yaml")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

// Stable name for the Docker image and deploy scripts.
tasks.bootJar {
    archiveFileName = "app.jar"
}

tasks.withType<Test> {
    // Java2D text rendering in tests must not try to open a window.
    systemProperty("java.awt.headless", "true")
}

tasks.test {
    useJUnitPlatform { excludeTags("e2e") }
}

// Black-box tests against an already running instance (local, Docker, or a remote server):
//   ./gradlew e2eTest -Pe2e.baseUrl=http://localhost:8080 -Pe2e.apiKey=<key> [-Pe2e.requireAi=true]
tasks.register<Test>("e2eTest") {
    description = "Runs end-to-end tests against a running story-builder (-Pe2e.baseUrl, default http://localhost:8080)."
    group = "verification"
    val testSources = sourceSets.test.get()
    testClassesDirs = testSources.output.classesDirs
    classpath = testSources.runtimeClasspath
    useJUnitPlatform { includeTags("e2e") }
    systemProperty("e2e.baseUrl", providers.gradleProperty("e2e.baseUrl").getOrElse("http://localhost:8080"))
    systemProperty("e2e.apiKey", providers.gradleProperty("e2e.apiKey").getOrElse(""))
    systemProperty("e2e.requireAi", providers.gradleProperty("e2e.requireAi").getOrElse("false"))
    // The target is external state, so never treat a previous run as up to date.
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = TestExceptionFormat.FULL
    }
}
