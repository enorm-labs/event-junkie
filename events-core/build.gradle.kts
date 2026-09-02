plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
    `java-library`
    `maven-publish`
    `java-test-fixtures`
}

// Use the same Java toolchain as events-bff and events-importer so the
// compiled library bytecode is compatible with its consumers.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(property("java.version").toString().toInt())
    }
    withSourcesJar()
}

repositories {
    mavenCentral()
}

// This module applies `io.spring.dependency-management` but NOT the Spring Boot plugin (it is a
// library, not an app), so no Boot BOM reaches it and Modulith's transitives choose the versions.
// That gap is easy to miss: events-bff and events-importer resolve what Boot pins while this
// module quietly keeps the older artifact, and the CVE scan reads every module.
//
// Importing the Boot BOM here is NOT the fix. Without the Boot plugin nothing aligns the BOM's
// `kotlin.version` with the Kotlin plugin's, so the BOM forces its own Kotlin onto the
// compiler-plugin classpath and `compileKotlin` dies with a null plugin classpath.
// `spring-framework-bom` carries no such risk: it manages `org.springframework:spring-*` and
// nothing else, so it raises the whole Framework family without naming each artifact.
dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:${property("spring-modulith.version")}")
        mavenBom("org.springframework:spring-framework-bom:${property("spring-framework-bom.version")}")
    }
}

dependencies {
    // Spring Modulith
    api("org.springframework.modulith:spring-modulith-starter-core")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")

    // Kotlin Logging — idiomatic SLF4J wrapper for Kotlin
    // See: https://github.com/oshai/kotlin-logging
    implementation("io.github.oshai:kotlin-logging-jvm:${property("kotlin-logging.version")}")

    testImplementation(kotlin("test"))

    // Pinned one artifact at a time because no BOM above manages it, reusing the gradle.properties
    // property so the two cannot drift. Consumers of the published artifact resolve log4j through
    // their own BOM; this constraint is about this module's own classpath, which is what the CVE
    // scan sees.
    constraints {
        implementation("org.apache.logging.log4j:log4j-api:${property("log4j-api.version")}") {
            because("2.25.4 (via spring-modulith-starter-core) is affected by CVE-2026-49844, fixed in 2.25.5")
        }
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
    jvmToolchain(property("java.version").toString().toInt())
}

tasks.test {
    useJUnitPlatform()

    // SchemaConfigurationTest defends a repo-wide invariant — that no hand-written statement in
    // either application names the schema literally — so it reads the sibling modules' sources at
    // runtime. Those files are not otherwise inputs to this task, which means Gradle would report it
    // UP-TO-DATE after a change in events-bff or events-importer and the guard would never run on the
    // change it exists to catch. Verified: without these lines an injected `events.` literal did not
    // fail the build, because the test simply did not re-execute.
    //
    // `withPropertyName` + PathSensitivity.RELATIVE keeps the build cache usable across machines.
    listOf("events-bff", "events-importer").forEach { module ->
        inputs
            .files(fileTree(rootProject.layout.projectDirectory.dir("$module/src/main")))
            .withPropertyName("$module-main-sources")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
}

// events-core is a pure domain library: almost every class is a plain data class, a Spring
// Modulith marker, or a test-fixture factory with no executable logic, so Kover counts their
// synthetic/unused members as "uncovered" and drives the module coverage down to a meaningless
// number (~11%). Exclude those so the metric reflects the code that actually carries logic — the
// enum `parseOrDefault` companions (EventType/EventStatus/ArtistRole) and MoneyExtensions, both of
// which remain measured and tested.
//
// The `*Module` / `*Fixtures` patterns are NOT repeated here: they apply to every module and are
// configured once in the root build's `subprojects` block. What is left is the part specific to
// this module — the domain data classes, listed by exact name so the BFF/importer `*Entity`
// persistence classes are not caught by a pattern and stay measured.
kover {
    reports {
        filters {
            excludes {
                classes(
                    // Plain domain data classes — no logic, only synthetic members.
                    "de.norm.events.artist.Artist",
                    "de.norm.events.event.Event",
                    "de.norm.events.event.LineupEntry",
                    "de.norm.events.genretag.GenreTag",
                    "de.norm.events.promoter.Promoter",
                    "de.norm.events.venue.Venue"
                )
            }
        }
    }
}
