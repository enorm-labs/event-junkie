package de.norm.events

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration

/**
 * The window [#263](https://github.com/enorm-labs/event-junkie/issues/263) measured, reproduced as a
 * regression test: **the database is up and the schema is not there.**
 *
 * That distinction is the whole of [#438](https://github.com/enorm-labs/event-junkie/issues/438).
 * On k3d the BFF reported Ready 1.2 seconds before the importer's Flyway migrations created the
 * schema it queries, with PostgreSQL healthy throughout and zero restarts — so Kubernetes routed
 * traffic to a pod whose every query would fail. Nothing about a reachable database was wrong, which
 * is why the stock `r2dbc` indicator would have reported `UP` for the entire window and closed
 * nothing. This test asserts that directly: `r2dbc` is `UP` and the group is still `DOWN`.
 *
 * The state is produced by pointing `spring.r2dbc.properties.schema` at a schema that was never
 * created, rather than by racing a real migration. That is deterministic, it needs no cluster, and it
 * is the same thing the indicator sees — a query against a relation that does not exist. Flyway is
 * off for the same reason: this context is a BFF that started before anything migrated.
 *
 * **The k3d rehearsal confirmed the fix once; this is the half that keeps confirming it.** On
 * 2026-08-18 the BFF reported Ready about four seconds *after* Flyway completed, with zero restarts —
 * the ordering inverted. A one-off measurement cannot fail later, and this can.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.r2dbc.properties.schema=not_migrated_yet",
        "spring.flyway.enabled=false"
    ]
)
@Import(PostgresTestcontainersConfiguration::class)
class ReadinessWithoutSchemaTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var context: ApplicationContext

    private val webTestClient: WebTestClient by lazy {
        WebTestClient
            .bindToServer()
            .baseUrl("http://localhost:$port")
            .responseTimeout(Duration.ofSeconds(30))
            .build()
    }

    @Test
    fun `readiness answers 503 while the schema does not exist`(): Unit =
        runBlocking {
            // 503 is the contract Kubernetes actually reads: a DOWN readiness group takes the pod out
            // of the Service's endpoints, so Traefik answers with no healthy backend instead of the
            // pod answering 200-framed query errors. Before #438 this was a 200.
            webTestClient
                .get()
                .uri("/actuator/health/readiness")
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("DOWN")
                .jsonPath("$.components.eventsSchema.status")
                .isEqualTo("DOWN")
                // The finding this whole change rests on: the database is fine. `r2dbc` calls
                // Connection.validate(REMOTE) and runs no query, so adding it to the readiness group
                // and stopping there would have left #263's window exactly as wide as it was.
                .jsonPath("$.components.r2dbc.status")
                .isEqualTo("UP")
        }

    @Test
    fun `liveness stays UP, so Kubernetes does not restart the pod`(): Unit =
        runBlocking {
            // ADR-018. A pod waiting for migrations is not a wedged pod. If liveness failed here the
            // startup probe would kill the container after 30 × 5s and a first install would
            // crash-loop — the behaviour the chart README used to wrongly claim, made real.
            webTestClient
                .get()
                .uri("/actuator/health/liveness")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("UP")
        }

    @Test
    fun `the application context still starts, because that is the point`(): Unit =
        runBlocking {
            // A BFF that refused to start without a schema would be a different design — one where
            // the chart needs an init container or a Helm hook to order the two workloads. It
            // deliberately is not: it starts, stays un-Ready, and joins the Service when the
            // importer has migrated. See the chart README.
            assert(context.containsBean("eventsSchemaHealthIndicator")) {
                "the indicator must be registered for the readiness group to reference it"
            }
        }
}
