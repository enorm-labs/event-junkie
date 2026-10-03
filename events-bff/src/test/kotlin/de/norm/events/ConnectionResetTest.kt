package de.norm.events

import com.github.dockerjava.api.model.ContainerNetwork
import eu.rekawek.toxiproxy.Proxy
import eu.rekawek.toxiproxy.ToxiproxyClient
import io.kotest.assertions.withClue
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
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.Network
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.toxiproxy.ToxiproxyContainer
import java.io.File
import java.time.Duration

/**
 * The R2DBC pool after every connection to PostgreSQL breaks at once, as on both clusters on
 * 2026-10-02 (#2318). Toxiproxy sits between the BFF and PostgreSQL. Without the `spring.r2dbc.pool`
 * settings in `application.yaml`, each dead pooled connection failed one request after the database
 * was back: ten 500s here, sixteen across two replicas in production.
 *
 * Its own context: the pool connects through the proxy and Flyway does not, and the response
 * cache is off so that every request reaches the pool.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = ["app.api.cache.ttl-seconds=0"])
class ConnectionResetTest {
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

    /**
     * PostgreSQL ends every backend while its network is down, so the BFF never sees the connections
     * close. Each one answers its next query with an RST, the `Connection reset by peer` production logged.
     */
    @Test
    fun `answers after every pooled connection died without the pool noticing`() {
        warmUp()

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

        statusesOf(POOL_SIZE + 2) shouldContainOnly listOf(HttpStatus.OK.value())
    }

    /**
     * A PostgreSQL restart seen by the pool: every connection closed and new ones refused for a
     * moment. Requests in that window fail, and none fails once it ends.
     */
    @Test
    fun `fails only while the database is unreachable, and recovers on the next request`() {
        warmUp()

        proxy.disable()
        val duringOutage = statusesOf(OUTAGE_REQUESTS)
        proxy.enable()

        duringOutage shouldContainOnly listOf(HttpStatus.INTERNAL_SERVER_ERROR.value())
        statusesOf(POOL_SIZE + 2) shouldContainOnly listOf(HttpStatus.OK.value())
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

    /** As many concurrent requests as the pool has connections, a few times over, so every connection is open. */
    private fun warmUp() = repeat(WARM_UP_ROUNDS) { (1..POOL_SIZE).toList().parallelStream().forEach { _ -> getVenues().expectStatus().isOk } }

    private fun statusesOf(requests: Int): List<Int> = (1..requests).map { getVenues().returnResult(String::class.java).status.value() }

    private fun getVenues() = client.get().uri("/venues").exchange()

    private fun poolBlock(path: String): List<String> {
        val lines = File(path).readLines()
        val start = lines.indexOfFirst { it == "    pool:" }
        withClue("no `spring.r2dbc.pool` block in $path") { (start >= 0) shouldBe true }
        return lines.drop(start + 1).takeWhile { it.startsWith("      ") }.map { it.trim() }
    }

    companion object {
        private const val POOL_SIZE = 10
        private const val WARM_UP_ROUNDS = 3
        private const val OUTAGE_REQUESTS = 3
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
