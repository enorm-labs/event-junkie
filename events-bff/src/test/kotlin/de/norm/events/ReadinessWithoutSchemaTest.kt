package de.norm.events

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.web.reactive.server.WebTestClient
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Duration

/**
 * The window [#263](https://github.com/enorm-labs/event-junkie/issues/263) measured, reproduced as a
 * regression test: **the database is up and the schema is not there.**
 *
 * That distinction is the whole of [#438](https://github.com/enorm-labs/event-junkie/issues/438). On
 * k3d the BFF reported Ready 1.2 seconds before the importer's Flyway migrations created the schema
 * it queries, with PostgreSQL healthy throughout — so Kubernetes routed traffic to a pod whose every
 * query would fail. Nothing about a reachable database was wrong, which is why the stock `r2dbc`
 * indicator would have reported `UP` for the entire window and closed nothing. This test asserts
 * that directly: `r2dbc` is `UP` and the readiness group is still `DOWN`.
 *
 * The state is produced by starting this context against **its own PostgreSQL, with Flyway off**,
 * rather than by racing a real migration: deterministic, no cluster needed, and exactly what the
 * indicator sees during the real window — a query against a relation that does not exist yet, on a
 * database that is perfectly healthy. The probe resolves `EVENTS_SCHEMA` like every other statement
 * (#540) and so cannot be aimed at a schema nobody created; a dedicated container is what replaces
 * that lever. `withDatabaseName` and `withReuse(false)` guarantee the `events` schema is absent no
 * matter what any other test in this JVM has migrated, which the shared
 * `PostgresTestcontainersConfiguration` cannot promise.
 *
 * The k3d rehearsal confirmed the fix once; this is the half that keeps confirming it — a one-off
 * measurement cannot fail later, and this can.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = ["spring.flyway.enabled=false"]
)
@Import(ReadinessWithoutSchemaTest.UnmigratedPostgres::class)
class ReadinessWithoutSchemaTest {
    /**
     * A PostgreSQL nothing has ever migrated.
     *
     * Deliberately **not** the shared [PostgresTestcontainersConfiguration]: that one carries
     * `withReuse(true)`, so whether it hands back a container some other context already ran Flyway
     * against depends on `testcontainers.reuse.enable` in the developer's environment. This test's
     * whole premise is that `events.event` does not exist, and a premise that holds on one machine
     * and not another is not a premise — it is a flake waiting for CI.
     */
    @TestConfiguration(proxyBeanMethods = false)
    class UnmigratedPostgres {
        @Bean
        @ServiceConnection(name = "postgres")
        fun unmigratedPostgres(): PostgreSQLContainer =
            PostgreSQLContainer("postgres:18.3-alpine")
                .withDatabaseName("never_migrated")
                .withReuse(false)
    }

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var context: ApplicationContext

    private val rootClient: WebTestClient by lazy {
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
            rootClient
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
            // crash-loop.
            rootClient
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
