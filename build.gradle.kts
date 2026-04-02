import org.gradle.api.tasks.WriteProperties
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
    id("org.jetbrains.intellij.platform") version "2.13.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',').filter(String::isNotBlank) })
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    // Use a regular local IDE dev instance; split mode is for remote-development flows
    // and produces a broken frontend/backend startup shape for this plugin project.
    splitMode = false

    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md: $start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n")
            }
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }
}

val generatePluginBuildInfo by tasks.registering(WriteProperties::class) {
    destinationFile = layout.buildDirectory.file("generated/resources/ijmcp-plugin.properties")
    comment = null
    encoding = "UTF-8"
    property("version", providers.gradleProperty("pluginVersion").get())
    property("sinceBuild", providers.gradleProperty("pluginSinceBuild").get())
    property("untilBuild", providers.gradleProperty("pluginUntilBuild").get())
}

tasks {
    processResources {
        from(generatePluginBuildInfo)
    }

    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }
}
