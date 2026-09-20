package de.norm.events

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryMetadata
import io.r2dbc.spi.R2dbcNonTransientResourceException
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.reactivestreams.Publisher
import org.springframework.boot.health.contributor.Status
import org.springframework.r2dbc.core.DatabaseClient
import reactor.core.publisher.Mono

/**
 * `/actuator/health/readiness` means "can serve", and `/actuator/health/liveness` does not.
 * [ReadinessGroupConfigTest] asserts the configuration files; this asserts the behaviour. The
 * contract Kubernetes reads is the HTTP status: a `DOWN` group answers `503`. The component
 * assertions exist because `r2dbc` and `eventsSchema` are deliberately two contributors.
 */
class ReadinessProbeTest : BaseControllerTest() {
    @Test
    fun `readiness is UP and names both database components`(): Unit =
        runBlocking {
            // `cleanUp` has truncated every table, so this runs against an empty events.event, the state of
            // a first install: a probe query returning no rows would report UNKNOWN and block readiness on a
            // healthy deployment (EventsSchemaHealthIndicator has why the query is an EXISTS).
            rootClient
                .get()
                .uri("/actuator/health/readiness")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("UP")
                .jsonPath("$.components.readinessState.status")
                .isEqualTo("UP")
                .jsonPath("$.components.r2dbc.status")
                .isEqualTo("UP")
                .jsonPath("$.components.eventsSchema.status")
                .isEqualTo("UP")
        }

    @Test
    fun `liveness carries no database component`(): Unit =
        runBlocking {
            // ADR-018: a database-dependent liveness probe turns an outage into a crash-loop that recovers
            // more slowly than the database.
            rootClient
                .get()
                .uri("/actuator/health/liveness")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.components.livenessState.status")
                .isEqualTo("UP")
                .jsonPath("$.components.r2dbc")
                .doesNotExist()
                .jsonPath("$.components.eventsSchema")
                .doesNotExist()
        }

    @Test
    fun `a database the BFF cannot query reports DOWN`(): Unit =
        runBlocking {
            // The failure path, through a ConnectionFactory that always errors: the probe resolves
            // EVENTS_SCHEMA like every statement (#540), so it cannot be aimed at a bogus schema, and the
            // schema-missing case lives in ReadinessWithoutSchemaTest. The metadata still says PostgreSQL so
            // DatabaseClient resolves a dialect without connecting; only `create()` fails.
            val unreachable =
                object : ConnectionFactory {
                    override fun create(): Publisher<out Connection> = Mono.error(R2dbcNonTransientResourceException("connection refused"))

                    override fun getMetadata(): ConnectionFactoryMetadata = ConnectionFactoryMetadata { "PostgreSQL" }
                }
            val health = EventsSchemaHealthIndicator(DatabaseClient.create(unreachable)).health().awaitSingle()

            withClue(
                "a query that cannot run must fail readiness, or the BFF is Ready before it can serve again (#438)"
            ) {
                health.status shouldBe Status.DOWN
            }
        }
}
