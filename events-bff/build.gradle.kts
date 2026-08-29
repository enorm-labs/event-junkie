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

// `springBoot { buildInfo }` — which stamps the version and commit this module serves at
// `GET /meta` and `/actuator/info` — is configured once for every Boot application in the root
// build. See docs/LEGAL.md §4.3.

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

    // Object storage — the cached venue images the BFF serves from our own origin (ADR-019).
    // `apache-client` is excluded for the same reason as in the importer: the `s3` artifact pulls
    // both HTTP implementations, the async client uses Netty, and the unused one is dead weight that
    // still has to be patched every time it takes a finding.
    implementation("software.amazon.awssdk:s3:${property("awssdk.version")}") {
        exclude(group = "software.amazon.awssdk", module = "apache-client")
    }
    implementation("software.amazon.awssdk:netty-nio-client:${property("awssdk.version")}")

    // The read-through cache in front of that bucket (#847). Version-managed by Boot's BOM, so this
    // pin does not exist and cannot go stale. Hetzner Object Storage is Ceph on spinning disks, so a
    // miss is a seek — and without a cache every first visitor to a page pays one per image.
    implementation("com.github.ben-manes.caffeine:caffeine")

    // Logging — idiomatic SLF4J wrapper (see: https://github.com/oshai/kotlin-logging)
    implementation("io.github.oshai:kotlin-logging-jvm:${property("kotlin-logging.version")}")

    // Kotlin
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation("tools.jackson.module:jackson-module-kotlin")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")

    // Dev Tools
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")

    // Testing
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Testcontainers
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-r2dbc")
    // A real S3 API for the serving tests. A mocked client would prove the code compiles; what has
    // to hold is that a key written by the importer reads back through this client's configuration.
    testImplementation("org.testcontainers:testcontainers-minio")

    // Flyway (test only) — the BFF owns no migrations; integration tests provision the schema
    // by running the importer's existing migrations (via a filesystem location, see Test config below).
    // This keeps the BFF's read entities verified against the real schema with zero DDL duplication.
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
    // Point Flyway at the importer's migrations using an absolute filesystem path so the
    // location is independent of the test working directory.
    systemProperty(
        "spring.flyway.locations",
        "filesystem:${rootProject.projectDir}/events-importer/src/main/resources/db/migration"
    )
}
