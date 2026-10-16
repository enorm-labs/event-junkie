package de.norm.events

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.health.contributor.AbstractReactiveHealthIndicator
import org.springframework.boot.health.contributor.Health
import org.springframework.core.io.ClassPathResource
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/**
 * Keeps readiness DOWN until the importer has applied every migration this build was compiled
 * against (#3384, ADR-018). Both Deployments roll at once, so without it a new pod serves queries
 * against columns that do not exist yet. A database ahead of the build is UP, so a rollback still serves.
 */
@Component
class SchemaVersionHealthIndicator(
    private val databaseClient: DatabaseClient
) : AbstractReactiveHealthIndicator("The schema version is not readable — readiness will report DOWN") {
    val expectedVersion: Int = readExpectedVersion()

    // The version filter keeps a non-numeric row from failing the cast and holding readiness forever.
    private val probeSql =
        "SELECT max(version::int) AS applied FROM $EVENTS_SCHEMA.flyway_schema_history " +
            "WHERE success AND version ~ '^[0-9]+$'"

    // Logged on a change only: the probe runs every ten seconds.
    private val lastBehind = AtomicReference<Boolean?>(null)

    override fun doHealthCheck(builder: Health.Builder): Mono<Health> =
        databaseClient
            .sql(probeSql)
            .map { row -> (row.get("applied", Number::class.java)?.toInt() ?: 0) }
            .one()
            .defaultIfEmpty(0)
            .map { applied ->
                val behind = applied < expectedVersion
                logTransition(applied, behind)
                (if (behind) builder.down() else builder.up())
                    .withDetail("expected", expectedVersion)
                    .withDetail("applied", applied)
                    .build()
            }

    private fun logTransition(
        applied: Int,
        behind: Boolean
    ) {
        val previous = lastBehind.getAndSet(behind)
        when {
            behind && previous != true -> {
                logger.info { "Readiness held: the database schema is at V$applied, this build needs V$expectedVersion" }
            }

            !behind && previous == true -> {
                logger.info { "Readiness released: the database schema reached V$applied, this build needs V$expectedVersion" }
            }
        }
    }

    companion object {
        /** Written by the `generateExpectedSchemaVersion` task in `events-bff/build.gradle.kts`. */
        const val EXPECTED_VERSION_RESOURCE = "META-INF/expected-schema-version"

        fun readExpectedVersion(): Int =
            ClassPathResource(EXPECTED_VERSION_RESOURCE)
                .inputStream
                .use {
                    it
                        .readAllBytes()
                        .decodeToString()
                        .trim()
                        .toInt()
                }
    }
}
