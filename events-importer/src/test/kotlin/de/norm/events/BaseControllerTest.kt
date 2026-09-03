package de.norm.events

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.await
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration

/**
 * Base class for importer controller integration tests.
 *
 * Provides a running server with a Testcontainers-backed PostgreSQL database,
 * a pre-configured [WebTestClient], and a [BeforeEach] hook that truncates all
 * tables so every test starts with a clean database.
 *
 * **`@AutoConfigureMetrics` is here rather than on the tests that need it (#965).** Spring Boot
 * disables metrics *export* in tests by default — `management.defaults.metrics.export.enabled` is
 * forced false by the test context customiser, so `PrometheusMetricsExportAutoConfiguration` never
 * applies and no amount of exposure configuration produces the endpoint. A test carrying the
 * annotation itself forks a second cached context, with its own container and its own pool, for one
 * property. On the base it costs nothing measurable: the importer suite got ~6s *faster* when the
 * fork went away. It affects tests only; production is unaffected.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMetrics
@Import(PostgresTestcontainersConfiguration::class)
@Suppress("AbstractClassCanBeConcreteClass") // The shared setup for the suites below it; an instance of it alone tests nothing.
abstract class BaseControllerTest {
    @LocalServerPort
    protected var port: Int = 0

    @Autowired
    protected lateinit var databaseClient: DatabaseClient

    /**
     * The client every controller test issues requests through.
     *
     * **`responseTimeout` is set deliberately, and 30 seconds is a crash guard rather than a
     * performance assertion (#504.)** Left unset, `WebTestClient` uses Spring's documented default
     * of **5 seconds**, and nothing in this repository had chosen that number. It is the only bound
     * on a hung request — there is no JUnit platform timeout and no timeout on the Gradle `Test`
     * task — so removing it entirely would turn a deadlock into a stalled build.
     *
     * Five seconds is too tight for what it guards. These endpoints answer in about 50 ms once warm,
     * but the **first** request in a `@SpringBootTest` class pays for a freshly started context and a
     * Testcontainers PostgreSQL: measured at 1.3 s on an idle laptop, and a loaded CI runner
     * multiplies that. `ArtistControllerTest` timed out on exactly that path — its own duplicate-name
     * test runs in 48 ms, one of the fastest in the class, so nothing was slow except the runner.
     *
     * Thirty seconds is roughly 600× the steady-state cost and 20× the cold start. Exceeding it means
     * something is genuinely broken, which is the only thing a test-suite timeout should ever claim.
     *
     * **MIRRORED IN THE OTHER MODULE'S `BaseControllerTest` — change both or neither.** The two files
     * are deliberate twins, like the per-cluster cert-manager manifests: a value that differs between
     * them would produce a suite that is flaky in one module and not the other, for a reason nobody
     * would think to compare.
     */
    protected val webTestClient: WebTestClient by lazy {
        WebTestClient
            .bindToServer()
            .baseUrl("http://localhost:$port")
            .responseTimeout(RESPONSE_TIMEOUT)
            .build()
    }

    private companion object {
        val RESPONSE_TIMEOUT: Duration = Duration.ofSeconds(30)
    }

    /** Truncates all domain tables before each test to ensure a clean state. */
    @BeforeEach
    fun cleanUp() =
        runBlocking {
            databaseClient
                .sql(
                    // cached_image has no foreign key to any of the others, so CASCADE does not
                    // reach it and it has to be named. A table missing from this list leaks rows
                    // between tests, which shows up as a neighbouring test failing.
                    "TRUNCATE TABLE events.data_quality_snapshot, events.event_source, events.event_genre_tag, " +
                        "events.event_promoter, events.event_artist, events.event, events.genre_tag, events.promoter, " +
                        "events.artist, events.venue, events.cached_image_variant, events.cached_image CASCADE"
                ).await()
        }
}
