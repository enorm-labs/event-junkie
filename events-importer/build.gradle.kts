plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(property("java.version").toString().toInt())
    }
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:${property("spring-modulith.version")}")
    }
}

dependencies {
    // Shared domain model and utilities from the events-core library module
    implementation(project(":events-core"))
    testImplementation(testFixtures(project(":events-core")))

    // Spring Modulith – enforces modular application structure and provides
    // event publication registry, observability, and documentation support.
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")

    // Spring Actuator
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // The Prometheus exposition format, per PLATFORM_SETUP.md §7 and ADR-015. Version comes from the
    // Boot BOM — do not pin it in gradle.properties; that file's version block is for BOM *overrides*
    // forced by a CVE, and an ordinary pin there would silently hold this behind future Boot releases.
    //
    // Deliberately a registry, not the OTLP exporter. ADR-015 adopted OpenObserve on trial with a
    // written exit, and the property that makes the exit cheap is that both apps emit vendor-neutral
    // Prometheus-format metrics — so swapping the backend is a Helm release and a datasource, never a
    // re-instrumentation.
    implementation("io.micrometer:micrometer-registry-prometheus")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")

    // Database
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    testImplementation("org.springframework.boot:spring-boot-starter-data-r2dbc-test")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework:spring-jdbc")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.postgresql:r2dbc-postgresql")

    // SCRAM authentication for r2dbc-postgresql. Constrained rather than declared, because it
    // is a pure transitive: r2dbc-postgresql pins 3.2 in both 1.1.1 and 1.1.2, so upgrading it
    // does not move scram. Drop this block once r2dbc-postgresql ships 3.3+ itself.
    constraints {
        runtimeOnly("com.ongres.scram:scram-client:${property("scram.version")}") {
            because("3.2 is affected by CVE-2026-53712 (high), fixed in 3.3")
        }
        runtimeOnly("com.ongres.scram:scram-common:${property("scram.version")}") {
            because("3.2 is affected by CVE-2026-53712 (high), fixed in 3.3")
        }
    }

    // Validation – Bean Validation API for request body validation (@Valid, @NotBlank, etc.)
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Web
    implementation("org.springframework.boot:spring-boot-starter-webclient")
    testImplementation("org.springframework.boot:spring-boot-starter-webclient-test")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")

    // SpringDoc OpenAPI – provides Swagger UI and OpenAPI spec generation for WebFlux
    // See: https://springdoc.org/#getting-started
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:${property("springdoc.version")}")

    // Swagger UI's webjar, constrained rather than declared, for the same reason as scram above:
    // it is a pure transitive of the springdoc starter, which pins 5.32.11. See gradle.properties
    // for why the pin exists and what removes it.
    constraints {
        implementation("org.webjars:swagger-ui:${property("swagger-ui.version")}") {
            because("5.32.11 bundles DOMPurify 3.4.12, affected by GHSA-55q2-fjhq-7xh7; 5.32.13 bundles 3.4.13")
        }
    }

    // Kotlin
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation("tools.jackson.module:jackson-module-kotlin")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")

    // crawler-commons – robots.txt parsing for RobotsTxtFilter (ADR-007 best-practice #1).
    // See: https://github.com/crawler-commons/crawler-commons
    implementation("com.github.crawler-commons:crawler-commons:${property("crawler-commons.version")}")

    // Kotlin Logging – idiomatic Kotlin wrapper around SLF4J
    // See: https://github.com/oshai/kotlin-logging
    implementation("io.github.oshai:kotlin-logging-jvm:${property("kotlin-logging.version")}")

    // Slugify – generates URL-friendly slugs from arbitrary strings, with locale support
    // See: https://github.com/slugify/slugify
    implementation("com.github.slugify:slugify:${property("slugify.version")}")

    // Jsoup – robust HTML parser and CSS-selector-based scraper for importing
    // event data from venue websites. Used for parsing only (HTTP fetching goes
    // through Spring WebClient). See: https://jsoup.org/
    implementation("org.jsoup:jsoup:${property("jsoup.version")}")

    // Dev Tools
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")

    // Testing
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Kotest assertions – expressive matchers for readable test assertions
    // See: https://kotest.io/docs/assertions/assertions.html
    testImplementation("io.kotest:kotest-assertions-core:${property("kotest.version")}")

    // MockK – idiomatic Kotlin mocking library, preferred over Mockito for Kotlin tests
    // See: https://mockk.io/
    testImplementation("io.mockk:mockk:${property("mockk.version")}")

    // MockWebServer – scriptable local HTTP server for exercising the real WebClient
    // request pipeline (URL encoding, headers, status handling) end to end.
    // The `mockwebserver3` artifact is okhttp 5's current API. Deliberately not the legacy
    // `com.squareup.okhttp3:mockwebserver`, which still exists at 5.x only as a deprecation
    // bridge whose MockWebServer extends JUnit 4's ExternalResource — it would drag
    // junit:junit onto the test classpath of a JUnit 5-only project.
    // See: https://github.com/square/okhttp/tree/master/mockwebserver
    testImplementation("com.squareup.okhttp3:mockwebserver3:${property("mockwebserver.version")}")

    // Testcontainers
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-r2dbc")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
