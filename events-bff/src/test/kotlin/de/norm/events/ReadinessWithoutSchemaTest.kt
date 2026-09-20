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
 * The window #263 measured, as a regression test: the database is up and the schema is not
 * there. On k3d the BFF reported Ready 1.2 seconds before the importer's Flyway migrations
 * created the schema, with PostgreSQL healthy throughout, so Kubernetes routed traffic to a pod
 * whose every query would fail (#438); the stock `r2dbc` indicator would have reported `UP` for
 * the whole window. This asserts `r2dbc` is `UP` and the readiness group still `DOWN`.
 *
 * The state is produced by starting this context against its own PostgreSQL with Flyway off,
 * rather than racing a migration: the probe resolves `EVENTS_SCHEMA` like every other statement
 * (#540), so a dedicated container is what replaces that lever, and `withDatabaseName` plus
 * `withReuse(false)` guarantee the `events` schema is absent whatever other tests have migrated.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = ["spring.flyway.enabled=false"]
)
@Import(ReadinessWithoutSchemaTest.UnmigratedPostgres::class)
class ReadinessWithoutSchemaTest {
    /**
     * A PostgreSQL nothing has ever migrated, deliberately not the shared
     * [PostgresTestcontainersConfiguration], which Flyway has migrated. `withReuse(false)` stays
     * explicit although the shared configuration no longer asks for reuse (#954): the guarantee
     * must not depend on a decision in another file.
     */
    @TestConfiguration(proxyBeanMethods = false)
    class UnmigratedPostgres {
        @Suppress("MemberNameEqualsClassName") // @Bean takes the bean name from the method, so renaming it renames the bean.
        @Bean
        @ServiceConnection(name = "postgres")
        fun unmigratedPostgres(): PostgreSQLContainer =
            PostgreSQLContainer("postgres:18.3-alpine")
                .withDatabaseName("never_migrated")
                .withReuse(false)
    }

    @Suppress("VarCouldBeVal") // Spring writes this field after construction, which a val does not allow.
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
            // 503 is the contract Kubernetes reads: a DOWN readiness group takes the pod out of the
            // Service's endpoints. Before #438 this was a 200.
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
                // The finding this rests on: `r2dbc` calls Connection.validate(REMOTE) and runs no query, so it
                // alone would have left #263's window as wide as it was.
                .jsonPath("$.components.r2dbc.status")
                .isEqualTo("UP")
        }

    @Test
    fun `liveness stays UP, so Kubernetes does not restart the pod`(): Unit =
        runBlocking {
            // ADR-018: a pod waiting for migrations is not a wedged pod. A failing liveness here would
            // crash-loop a first install after the startup probe's 30 × 5s.
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
            // A BFF that refused to start without a schema would need an init container or a Helm hook to
            // order the two workloads. It starts, stays un-Ready, and joins the Service when the importer
            // has migrated (chart README).
            assert(context.containsBean("eventsSchemaHealthIndicator")) {
                "the indicator must be registered for the readiness group to reference it"
            }
        }
}
