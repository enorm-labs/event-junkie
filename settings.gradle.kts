rootProject.name = "event-junkie"

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}

// Plugin resolution must include Maven Central because the Spring Boot Gradle plugin
// is published there and may not always be mirrored on the Gradle Plugin Portal.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }

    plugins {
        kotlin("jvm") version "2.4.10"
        kotlin("plugin.spring") version "2.4.10"
        id("org.springframework.boot") version "4.1.0"
        id("io.spring.dependency-management") version "1.1.7"
        id("org.jetbrains.kotlinx.kover") version "0.9.9"
        id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
        // check this compatibility table: https://detekt.dev/docs/introduction/compatibility/
        id("dev.detekt") version "2.0.0-alpha.6"
        id("io.github.ben-manes.versions") version "0.61.0"
        id("org.owasp.dependencycheck") version "13.0.0"
        id("com.github.jk1.dependency-license-report") version "3.1.4"
    }
}

// All three modules are included as subprojects so events-bff and events-importer
// can declare a project(":events-core") dependency without needing a published artifact.
include("events-core")
include("events-bff")
include("events-importer")
