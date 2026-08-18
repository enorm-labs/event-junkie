package de.norm.events

import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryMetadata
import io.r2dbc.spi.R2dbcNonTransientResourceException
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.reactivestreams.Publisher
import org.springframework.boot.health.contributor.Status
import org.springframework.r2dbc.core.DatabaseClient
import reactor.core.publisher.Mono

/**
 * `/actuator/health/readiness` means "can serve", and `/actuator/health/liveness` does not.
 *
 * [ReadinessGroupConfigTest] asserts the configuration files; this asserts the behaviour they buy,
 * which is the half that would still be wrong if a contributor were named correctly and reported
 * nothing useful.
 *
 * The contract Kubernetes actually reads is the HTTP status, not the body — a `DOWN` group answers
 * `503` and the pod leaves the Service's endpoints. The component assertions exist because
 * `r2dbc` and `eventsSchema` are deliberately two contributors rather than one, and that only pays
 * off if both actually appear.
 */
class ReadinessProbeTest : BaseControllerTest() {
    @Test
    fun `readiness is UP and names both database components`(): Unit =
        runBlocking {
            // `cleanUp` has truncated every table, so this runs against an **empty** events.event —
            // which is the interesting case, not a degenerate one. It is the state of a first install,
            // and a probe query that returns no rows there would reach Health.Builder with no status
            // set and report UNKNOWN, blocking readiness on a perfectly healthy deployment. See
            // EventsSchemaHealthIndicator for why the query is an EXISTS rather than a LIMIT 1.
            webTestClient
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
            // ADR-018: a database-dependent liveness probe restarts every replica during an outage,
            // turning a recoverable outage into a crash-loop that recovers more slowly than the
            // database does. The group split is the thing being asserted here.
            webTestClient
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
            // The failure path, driven through a ConnectionFactory that always errors.
            //
            // **It used to pass a bogus schema name to the constructor, and #540 removed that lever
            // on purpose**: the probe now resolves EVENTS_SCHEMA like every other statement in the
            // application, precisely so it cannot be aimed at a schema the queries are not using. The
            // schema-specific case did not lose coverage — ReadinessWithoutSchemaTest asserts it end
            // to end against a real, deliberately un-migrated PostgreSQL, which is stronger evidence
            // than a renamed schema ever was. What remains here is the cheap, fast assertion that a
            // failing query becomes DOWN rather than an exception escaping the actuator endpoint.
            // The metadata still says PostgreSQL so DatabaseClient can resolve a dialect without
            // connecting; only `create()` fails, which is the state a dead database presents.
            val unreachable =
                object : ConnectionFactory {
                    override fun create(): Publisher<out Connection> = Mono.error(R2dbcNonTransientResourceException("connection refused"))

                    override fun getMetadata(): ConnectionFactoryMetadata = ConnectionFactoryMetadata { "PostgreSQL" }
                }
            val health = EventsSchemaHealthIndicator(DatabaseClient.create(unreachable)).health().awaitSingle()

            assertEquals(Status.DOWN, health.status) {
                "a query that cannot run must fail readiness, or the BFF is Ready before it can serve again (#438)"
            }
        }
}
