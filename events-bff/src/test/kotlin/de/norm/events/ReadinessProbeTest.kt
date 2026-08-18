package de.norm.events

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.health.contributor.Status

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
    fun `a schema the BFF cannot read reports DOWN`(): Unit =
        runBlocking {
            // The database is reachable throughout — only the schema is wrong. That is exactly the
            // shape of the failure #263 measured on k3d, where PostgreSQL was up the whole time and
            // the BFF was Ready 1.2 seconds before Flyway created the schema it queries. The stock
            // `r2dbc` indicator stays UP in this state, which is why it is not sufficient on its own.
            val health = EventsSchemaHealthIndicator(databaseClient, "no_such_schema").health().awaitSingle()

            assertEquals(Status.DOWN, health.status) {
                "a missing schema must fail readiness, or the BFF is Ready before it can serve again (#438)"
            }
        }
}
