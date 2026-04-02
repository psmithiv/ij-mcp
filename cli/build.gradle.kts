import org.gradle.api.tasks.WriteProperties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
    application
}

group = rootProject.group
version = rootProject.version

repositories {
    mavenCentral()
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
    testImplementation(kotlin("test"))
}

application {
    mainClass = "ai.plyxal.ijmcp.cli.IjMcpCliKt"
}

val generateCliBuildInfo by tasks.registering(WriteProperties::class) {
    destinationFile = layout.buildDirectory.file("generated/resources/ijmcp-cli.properties")
    comment = null
    encoding = "UTF-8"
    property("version", providers.gradleProperty("pluginVersion").get())
}

tasks.processResources {
    from(generateCliBuildInfo)
}
