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

dependencies {
    // `compileOnly`: detekt supplies its own API at runtime, and bundling a second copy onto the
    // plugin classpath is how a custom rule ends up loaded by the wrong classloader.
    compileOnly("dev.detekt:detekt-api:${property("detekt.version")}")

    testImplementation("dev.detekt:detekt-api:${property("detekt.version")}")
    testImplementation("dev.detekt:detekt-test:${property("detekt.version")}")
    testImplementation(kotlin("test"))
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.test {
    useJUnitPlatform()
}
