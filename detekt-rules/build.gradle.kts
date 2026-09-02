plugins {
    kotlin("jvm")
}

// Build tooling, not product code: this module is only ever loaded onto detekt's plugin classpath
// (`detektPlugins` in the root build), never packaged into an image or published. That is why it is
// left out of the Kover aggregate, the licence report and the OWASP scan — all three in the root
// build, each next to the reason.

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(property("java.version").toString().toInt())
    }
}

repositories {
    mavenCentral()
}

// The version of detekt this module compiles against, taken from the detekt plugin itself rather
// than from a property somebody has to keep in step. `toolVersion` is what the plugin resolves for
// analysis, so a rule built against it can never meet a different API at runtime — the failure mode
// a hand-maintained second copy of the version produces, and it surfaces as a NoSuchMethodError
// during `detekt` rather than as anything that looks like a version mismatch.
//
// The plugin is applied by the root build's `subprojects` block, which is evaluated before this
// script, so the extension is already there to read.
val detektVersion: Provider<String> = the<dev.detekt.gradle.extensions.DetektExtension>().toolVersion

dependencies {
    // `compileOnly`: detekt supplies its own API at runtime, and bundling a second copy onto the
    // plugin classpath is how a custom rule ends up loaded by the wrong classloader.
    compileOnly(detektVersion.map { "dev.detekt:detekt-api:$it" })

    testImplementation(detektVersion.map { "dev.detekt:detekt-api:$it" })
    testImplementation(detektVersion.map { "dev.detekt:detekt-test:$it" })
    testImplementation(kotlin("test"))

    // Kotest assertions – expressive matchers for readable test assertions
    // See: https://kotest.io/docs/assertions/assertions.html
    testImplementation("io.kotest:kotest-assertions-core:${property("kotest.version")}")
}

// Prints the version the module compiled against — the answer to "which detekt is this rule built
// for?" without reading two files and hoping they agree.
tasks.register("detektToolVersion") {
    val version = detektVersion
    doLast { logger.lifecycle("detekt-api: ${version.get()}") }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.test {
    useJUnitPlatform()
}
