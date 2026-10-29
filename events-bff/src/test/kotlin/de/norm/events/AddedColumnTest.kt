package de.norm.events

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import org.testcontainers.postgresql.PostgreSQLContainer
import java.io.File
import java.time.Duration

/**
 * The outgoing BFF while the new release's migration adds a column to a table it reads (#3648).
 * `findById` selects `venue.*`, so a statement the driver prepared before the `ALTER TABLE` changes
 * its result type and its transaction aborts. Production logged five 500s that way beside V178 and
 * V179, each a `25P02` on that `SELECT`.
 *
 * Its own container, because the test alters the schema, and the response cache is off so that
 * every request reaches the database.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = ["app.api.cache.ttl-seconds=0"])
class AddedColumnTest {
    @Suppress("VarCouldBeVal") // Spring writes this field after construction, which a val does not allow.
    @LocalServerPort
    private var port: Int = 0

    private val client: WebTestClient by lazy {
        WebTestClient
            .bindToServer()
            .baseUrl("http://localhost:$port/api")
            .responseTimeout(Duration.ofSeconds(30))
            .build()
    }

    @Test
    fun `answers on every pooled connection after a migration adds a column to a table it reads`() {
        psql(
            "WITH v AS (INSERT INTO events.venue (name, slug) VALUES ('Probe', '$SLUG') RETURNING id) " +
                "INSERT INTO events.event (venue_id, title, slug, event_date, source_id) SELECT id, 'Probe', '$SLUG', CURRENT_DATE + 7, 'probe:1' FROM v"
        )
        repeat(WARM_UP_ROUNDS) { (1..POOL_SIZE).toList().parallelStream().forEach { _ -> getEvent().expectStatus().isOk } }

        psql("ALTER TABLE events.venue ADD COLUMN added_by_test TEXT")

        statusesOf(POOL_SIZE + 2) shouldContainOnly listOf(HttpStatus.OK.value())
    }

    @Test
    fun `the test configuration disables the statement cache exactly as the shipped one`() {
        listOf("src/main/resources/application.yaml", "src/test/resources/application.yaml").forEach { path ->
            withClue("$path must set it, because the test file shadows the main one") {
                File(path).readLines().map { it.trim() } shouldContain "preparedStatementCacheQueries: 0"
            }
        }
    }

    private fun statusesOf(requests: Int): List<Int> = (1..requests).map { getEvent().returnResult(String::class.java).status.value() }

    private fun getEvent() = client.get().uri("/events/$SLUG").exchange()

    private fun psql(sql: String) {
        val result = postgres.execInContainer("psql", "-U", postgres.username, "-d", postgres.databaseName, "-v", "ON_ERROR_STOP=1", "-Atc", sql)
        withClue(result.stderr) { result.exitCode shouldBe 0 }
    }

    companion object {
        private const val POOL_SIZE = 10
        private const val WARM_UP_ROUNDS = 3
        private const val SLUG = "probe"

        private val postgres: PostgreSQLContainer = PostgreSQLContainer(POSTGRES_TEST_IMAGE)

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            postgres.start()
            registry.add("spring.r2dbc.url") { "r2dbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/${postgres.databaseName}" }
            registry.add("spring.r2dbc.username", postgres::getUsername)
            registry.add("spring.r2dbc.password", postgres::getPassword)
            registry.add("spring.flyway.url", postgres::getJdbcUrl)
            registry.add("spring.flyway.user", postgres::getUsername)
            registry.add("spring.flyway.password", postgres::getPassword)
        }
    }
}
