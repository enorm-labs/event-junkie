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

// `springBoot { buildInfo }`, which stamps the version and commit `GET /meta` serves, is
// configured once for every Boot application in the root build (docs/LEGAL.md §4.3).

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

    // Spring Modulith
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")

    // Spring Actuator
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // The Prometheus exposition format (PLATFORM_SETUP.md §7, ADR-015); version from the Boot BOM,
    // never pinned in gradle.properties, whose version block is for CVE-forced overrides. A
    // registry, not the OTLP exporter: ADR-015 adopted OpenObserve on trial, and swapping the backend
    // stays a Helm release rather than a re-instrumentation.
    implementation("io.micrometer:micrometer-registry-prometheus")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")

    // Database
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    testImplementation("org.springframework.boot:spring-boot-starter-data-r2dbc-test")
    implementation("org.springframework:spring-jdbc")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.postgresql:r2dbc-postgresql")

    // SCRAM for r2dbc-postgresql, constrained because it is a pure transitive: r2dbc-postgresql pins
    // 3.2. Drop once r2dbc-postgresql ships 3.3+ itself.
    constraints {
        runtimeOnly("com.ongres.scram:scram-client:${property("scram.version")}") {
            because("3.2 is affected by CVE-2026-53712 (high), fixed in 3.3")
        }
        runtimeOnly("com.ongres.scram:scram-common:${property("scram.version")}") {
            because("3.2 is affected by CVE-2026-53712 (high), fixed in 3.3")
        }
    }

    // Web
    implementation("org.springframework.boot:spring-boot-starter-webclient")
    testImplementation("org.springframework.boot:spring-boot-starter-webclient-test")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")

    // SpringDoc OpenAPI – provides Swagger UI and OpenAPI spec generation for WebFlux
    // See: https://springdoc.org/#getting-started
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:${property("springdoc.version")}")

    // Swagger UI's webjar, constrained for the same reason as scram: a pure transitive of the
    // springdoc starter (gradle.properties has why the pin exists and what removes it).
    constraints {
        implementation("org.webjars:swagger-ui:${property("swagger-ui.version")}") {
            because("5.32.11 bundles DOMPurify 3.4.12, affected by GHSA-55q2-fjhq-7xh7; 5.32.13 bundles 3.4.13")
        }
    }

    // Object storage for the cached venue images (ADR-019). `apache-client` excluded as in the
    // importer: the async client uses Netty, and the unused implementation still takes findings.
    implementation("software.amazon.awssdk:s3:${property("awssdk.version")}") {
        exclude(group = "software.amazon.awssdk", module = "apache-client")
    }
    implementation("software.amazon.awssdk:netty-nio-client:${property("awssdk.version")}")

    // The read-through cache in front of that bucket (#847), version-managed by Boot's BOM. Hetzner
    // Object Storage is Ceph on spinning disks, so a miss is a seek per image per first visitor.
    implementation("com.github.ben-manes.caffeine:caffeine")

    // Logging — idiomatic SLF4J wrapper (see: https://github.com/oshai/kotlin-logging)
    implementation("io.github.oshai:kotlin-logging-jvm:${property("kotlin-logging.version")}")

    // Kotlin
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    // Micrometer's ContextRegistry, how a request's log context survives the reactive chain (#380).
    // Transitive at runtime through Reactor, but LogContextConfiguration names the type, so declared.
    implementation("io.micrometer:context-propagation")
    implementation("tools.jackson.module:jackson-module-kotlin")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")

    // Dev Tools
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")

    // Testing
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Kotest assertions – expressive matchers for readable test assertions
    // See: https://kotest.io/docs/assertions/assertions.html
    testImplementation("io.kotest:kotest-assertions-core:${property("kotest.version")}")

    // Testcontainers
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-r2dbc")
    // A real S3 API for the serving tests: what has to hold is that a key written by the importer
    // reads back through this client's configuration.
    testImplementation("org.testcontainers:testcontainers-minio")

    // Flyway (test only): the BFF owns no migrations, so integration tests run the importer's
    // against the real schema with zero DDL duplication.
    testImplementation("org.springframework.boot:spring-boot-starter-flyway")
    testImplementation("org.flywaydb:flyway-database-postgresql")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // An absolute filesystem path, independent of the test working directory.
    systemProperty(
        "spring.flyway.locations",
        "filesystem:${rootProject.projectDir}/events-importer/src/main/resources/db/migration"
    )
}
