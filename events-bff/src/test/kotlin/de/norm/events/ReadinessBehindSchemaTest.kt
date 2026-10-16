package de.norm.events

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration

/**
 * The rollout #3384 found: the importer has not yet applied the migration this build reads, so
 * readiness answers 503 until it has. Its own context, because Flyway stops one version short here
 * and the shared context must stay fully migrated.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(PostgresTestcontainersConfiguration::class)
class ReadinessBehindSchemaTest {
    @Suppress("VarCouldBeVal") // Spring writes this field after construction, which a val does not allow.
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var flyway: Flyway

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    private val rootClient: WebTestClient by lazy {
        WebTestClient
            .bindToServer()
            .baseUrl("http://localhost:$port")
            .responseTimeout(Duration.ofSeconds(30))
            .build()
    }

    @Test
    fun `readiness answers 503 one migration short, then 200 once the last one is applied`(): Unit =
        runBlocking {
            val expected = SchemaVersionHealthIndicator.readExpectedVersion()
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val indicatorLogger = LoggerFactory.getLogger(SchemaVersionHealthIndicator::class.java) as Logger
            indicatorLogger.addAppender(appender)

            repeat(2) { readinessIsHeld(expected) }

            Flyway
                .configure()
                .configuration(flyway.configuration)
                .target(MigrationVersion.LATEST)
                .load()
                .migrate()

            readinessIsOk()

            // A database ahead of the build is what a rollback meets, and the old pods must still serve.
            databaseClient
                .sql(
                    "INSERT INTO $EVENTS_SCHEMA.flyway_schema_history " +
                        "(installed_rank, version, description, type, script, installed_by, execution_time, success) " +
                        "SELECT max(installed_rank) + 1, '${expected + 1}', 'ahead', 'SQL', 'V${expected + 1}__ahead.sql', " +
                        "current_user, 0, true FROM $EVENTS_SCHEMA.flyway_schema_history"
                ).fetch()
                .rowsUpdated()
                .awaitSingleOrNull()

            readinessIsOk()
            indicatorLogger.detachAppender(appender)

            withClue("the probe runs every ten seconds, so only a change of state is logged") {
                appender.list.map { it.level to it.formattedMessage.substringBefore(':') } shouldBe
                    listOf(Level.INFO to "Readiness held", Level.INFO to "Readiness released")
            }
        }

    private fun readinessIsHeld(expected: Int) {
        rootClient
            .get()
            .uri("/actuator/health/readiness")
            .exchange()
            .expectStatus()
            .isEqualTo(503)
            .expectBody()
            .jsonPath("$.components.schemaVersion.status")
            .isEqualTo("DOWN")
            .jsonPath("$.components.schemaVersion.details.expected")
            .isEqualTo(expected)
            // The schema exists and is readable, so only the version gate holds the pod back.
            .jsonPath("$.components.eventsSchema.status")
            .isEqualTo("UP")
    }

    private fun readinessIsOk() {
        rootClient
            .get()
            .uri("/actuator/health/readiness")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.components.schemaVersion.status")
            .isEqualTo("UP")
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun stopOneMigrationShort(registry: DynamicPropertyRegistry) {
            registry.add("spring.flyway.target") { (SchemaVersionHealthIndicator.readExpectedVersion() - 1).toString() }
        }
    }
}
