package de.norm.events

import com.github.dockerjava.api.model.ContainerNetwork
import de.norm.events.event.EventRepository
import eu.rekawek.toxiproxy.Proxy
import eu.rekawek.toxiproxy.ToxiproxyClient
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.await
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.Network
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.toxiproxy.ToxiproxyContainer
import java.io.File

/**
 * The R2DBC pool after every connection to PostgreSQL breaks at once, as when a systemd upgrade on
 * the production node restarted `systemd-networkd` on 2026-10-16 (#3383). Toxiproxy sits between the
 * importer and PostgreSQL. The BFF's `ConnectionResetTest` is the twin.
 *
 * Its own context: the pool connects through the proxy and Flyway does not.
 */
@SpringBootTest
class ConnectionResetTest {
    @Autowired
    private lateinit var eventRepository: EventRepository

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    /**
     * PostgreSQL ends every backend while its network is down, so the importer never sees the
     * connections close. Each one answers its next query with an RST, the `Connection reset by peer`
     * production logged.
     */
    @Test
    fun `the repository answers after every pooled connection died without the pool noticing`() {
        openEveryConnection()

        val docker = DockerClientFactory.instance().client()
        docker
            .disconnectFromNetworkCmd()
            .withNetworkId(network.id)
            .withContainerId(postgres.containerId)
            .exec()
        postgres.execInContainer("psql", "-U", postgres.username, "-d", postgres.databaseName, "-Atc", TERMINATE_CLIENT_BACKENDS)
        docker
            .connectToNetworkCmd()
            .withNetworkId(network.id)
            .withContainerId(postgres.containerId)
            .withContainerNetwork(ContainerNetwork().withAliases(POSTGRES_ALIAS))
            .exec()

        failuresOf(POOL_SIZE + 2).shouldBeEmpty()
    }

    /**
     * A PostgreSQL restart seen by the pool: every connection closed and new ones refused for a
     * moment. Queries in that window fail, and none fails once it ends.
     */
    @Test
    fun `fails only while the database is unreachable, and recovers on the next query`() {
        openEveryConnection()

        proxy.disable()
        val duringOutage = failuresOf(OUTAGE_QUERIES)
        proxy.enable()

        duringOutage.size shouldBe OUTAGE_QUERIES
        failuresOf(POOL_SIZE + 2).shouldBeEmpty()
    }

    @Test
    fun `the test configuration pools connections exactly as the shipped one`() {
        val main = poolBlock("src/main/resources/application.yaml")

        withClue("the test application.yaml shadows the main one, so the two pool blocks must match") {
            poolBlock("src/test/resources/application.yaml") shouldBe main
        }
        withClue("one acquire retry per pooled connection, so every dead one can be replaced: $main") {
            main.single { it.startsWith("acquire-retry:") } shouldBe "acquire-retry: $POOL_SIZE"
            main.single { it.startsWith("max-size:") } shouldBe "max-size: $POOL_SIZE"
        }
    }

    /** Holds one connection per pool slot at the same time, so the pool has opened every one. */
    private fun openEveryConnection() =
        runBlocking {
            coroutineScope {
                (1..POOL_SIZE).map { async { databaseClient.sql("SELECT pg_sleep(0.2)").await() } }.awaitAll()
            }
        }

    /** The exception of every query that failed, in order. */
    private fun failuresOf(queries: Int): List<String> =
        (1..queries).mapNotNull { runCatching { runBlocking { eventRepository.count() } }.exceptionOrNull()?.toString() }

    private fun poolBlock(path: String): List<String> {
        val lines = File(path).readLines()
        val start = lines.indexOfFirst { it == "    pool:" }
        withClue("no `spring.r2dbc.pool` block in $path") { (start >= 0) shouldBe true }
        return lines
            .drop(start + 1)
            .takeWhile { it.startsWith("      ") }
            .map { it.trim() }
            .filterNot { it.startsWith("#") }
    }

    companion object {
        private const val POOL_SIZE = 12
        private const val OUTAGE_QUERIES = 3
        private const val PROXY_PORT = 8666
        private const val POSTGRES_ALIAS = "postgres"
        private const val TERMINATE_CLIENT_BACKENDS =
            "SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity WHERE backend_type = 'client backend' AND pid <> pg_backend_pid()"

        private val network: Network = Network.newNetwork()

        private val postgres: PostgreSQLContainer =
            PostgreSQLContainer("postgres:18.3-alpine").withNetwork(network).withNetworkAliases(POSTGRES_ALIAS)

        private val toxiproxy: ToxiproxyContainer = ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.12.0").withNetwork(network)

        private lateinit var proxy: Proxy

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            postgres.start()
            toxiproxy.start()
            proxy = ToxiproxyClient(toxiproxy.host, toxiproxy.controlPort).createProxy("postgres", "0.0.0.0:$PROXY_PORT", "$POSTGRES_ALIAS:5432")
            val proxied = "${toxiproxy.host}:${toxiproxy.getMappedPort(PROXY_PORT)}"
            registry.add("spring.r2dbc.url") { "r2dbc:postgresql://$proxied/${postgres.databaseName}" }
            registry.add("spring.r2dbc.username", postgres::getUsername)
            registry.add("spring.r2dbc.password", postgres::getPassword)
            registry.add("spring.flyway.url", postgres::getJdbcUrl)
            registry.add("spring.flyway.user", postgres::getUsername)
            registry.add("spring.flyway.password", postgres::getPassword)
        }
    }
}
