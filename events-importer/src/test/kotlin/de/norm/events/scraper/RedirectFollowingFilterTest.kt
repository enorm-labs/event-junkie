package de.norm.events.scraper

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import de.norm.events.image.ImageUrlValidator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.Dispatchers
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpMethod
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.netty.resources.ConnectionProvider
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeMark

/**
 * [RedirectFollowingFilter] inside the real [SCRAPER_WEB_CLIENT] (#5417). Two servers stand for two hosts:
 * the venue at `127.0.0.1` and another host at `localhost`, because [RobotsRulesCache] and
 * [PerHostThrottlingFilter] key on the host name, not the port.
 *
 * The guard is the real [ImageUrlValidator] with the two servers' ports let through, so a request to either stands for
 * a request to a public host, and every other loopback, private or metadata address is refused as on the cluster
 * (#5444, #5448).
 */
class RedirectFollowingFilterTest {
    private val config = ScraperHttpClientConfig()
    private val properties = ScraperProperties(politeDelayMillis = 0)
    private lateinit var provider: ConnectionProvider
    private lateinit var venue: Host
    private lateinit var other: Host

    /** A loopback server the guard does not let through, so a test can show it received nothing. */
    private lateinit var internal: Host

    @BeforeEach
    fun start() {
        venue = Host("127.0.0.1")
        other = Host("localhost")
        internal = Host("127.0.0.1")
    }

    @AfterEach
    fun stop() {
        venue.server.close()
        other.server.close()
        internal.server.close()
        if (::provider.isInitialized) provider.dispose()
    }

    @Test
    fun `follows a redirect on the same host to the final page`() {
        venue.routes["/p"] = redirect(301, "/final")
        venue.routes["/final"] = page("final")

        get(client(), venue.url("/p")) shouldBe "final"

        venue.paths shouldContainExactly listOf("/robots.txt", "/p", "/final")
    }

    @Test
    fun `refuses a hop to a host whose robots txt disallows it, and sends that host nothing but the robots txt read`() {
        venue.routes["/ticket"] = redirect(302, other.url("/shop/tickets"))
        other.robots = "User-agent: *\nDisallow: /\n"
        other.routes["/shop/tickets"] = page("never")

        shouldThrow<RobotsDisallowedException> { get(client(), venue.url("/ticket")) }.url shouldBe other.url("/shop/tickets")

        other.paths shouldContainExactly listOf("/robots.txt")
    }

    @Test
    fun `refuses a first URL on a loopback address and sends it nothing, not even a robots txt read`() {
        internal.routes["/admin"] = page("never")

        val refused = shouldThrow<RequestRefusedException> { get(client(), internal.url("/admin")) }

        refused.fromHost shouldBe null
        refused.reason shouldBe "resolves to a blocked address"
        internal.requests.shouldBeEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = ["http://169.254.169.254/latest/meta-data/", "http://$PRIVATE_HOST/", "file:///etc/passwd"])
    fun `refuses a first URL to the metadata address, to a host that resolves to a private address, and to another scheme`(url: String) {
        val refused = shouldThrow<RequestRefusedException> { get(client(), url) }

        refused.fromHost shouldBe null
        refused.targetHost shouldBe URI(url).host
    }

    @Test
    fun `logs a refused first URL once as a WARN with the host and the reason, and no query string`() {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger(RedirectFollowingFilter::class.java) as Logger
        logger.addAppender(appender)

        try {
            shouldThrow<RequestRefusedException> { get(client(), "http://169.254.169.254/latest/meta-data/?token=secret") }.message shouldNotContain "?"
        } finally {
            logger.detachAppender(appender)
        }

        val line = appender.list.single()
        line.level shouldBe Level.WARN
        line.formattedMessage shouldBe "Refused a request to 169.254.169.254: resolves to a blocked address"
    }

    @Test
    fun `refuses a hop to a loopback address and sends it nothing`() {
        venue.routes["/p"] = redirect(302, internal.url("/admin"))
        internal.routes["/admin"] = page("never")

        shouldThrow<RequestRefusedException> { get(client(), venue.url("/p")) }.reason shouldBe "resolves to a blocked address"

        internal.requests.shouldBeEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = ["http://169.254.169.254/latest/meta-data/", "http://$PRIVATE_HOST/", "file:///etc/passwd"])
    fun `refuses a hop to the metadata address, to a host that resolves to a private address, and to another scheme`(location: String) {
        venue.routes["/p"] = redirect(302, location)

        val refused = shouldThrow<RequestRefusedException> { get(client(), venue.url("/p")) }

        refused.fromHost shouldBe "127.0.0.1"
        refused.targetHost shouldBe URI(location).host
    }

    @Test
    fun `logs a refused hop once as a WARN with both hosts and the reason, and no query string`() {
        venue.routes["/p?id=7"] = redirect(302, "http://169.254.169.254/latest/meta-data/?token=secret")
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger(RedirectFollowingFilter::class.java) as Logger
        logger.addAppender(appender)

        try {
            shouldThrow<RequestRefusedException> { get(client(), venue.url("/p?id=7")) }.message shouldNotContain "?"
        } finally {
            logger.detachAppender(appender)
        }

        val line = appender.list.single()
        line.level shouldBe Level.WARN
        line.formattedMessage shouldBe "Refused a 302 redirect from 127.0.0.1 to 169.254.169.254: resolves to a blocked address"
        line.keyValuePairs.associate { it.key to it.value } shouldBe mapOf(LogFields.HTTP_STATUS to 302)
    }

    @Test
    fun `fails on the sixth redirect`() {
        (0..MAX).forEach { venue.routes["/r$it"] = redirect(302, "/r${it + 1}") }

        shouldThrow<RedirectFailedException> { get(client(), venue.url("/r0")) }.message shouldContain "more than 5 redirects"

        venue.paths.filter { it != "/robots.txt" } shouldBe (0..MAX).map { "/r$it" }
    }

    @Test
    fun `follows five redirects`() {
        (0 until MAX).forEach { venue.routes["/r$it"] = redirect(307, "/r${it + 1}") }
        venue.routes["/r$MAX"] = page("fifth")

        get(client(), venue.url("/r0")) shouldBe "fifth"
    }

    @Test
    fun `waits the target host's throttle delay before the hop`() {
        val clock = VirtualClock()
        venue.routes["/ticket"] = redirect(302, other.url("/shop"))
        other.routes["/shop"] = page("shop")

        get(client(PerHostThrottlingFilter(0, clock, mapOf("localhost" to 5.seconds))), venue.url("/ticket")) shouldBe "shop"

        // The other host's robots.txt read sets its timer, and the hop waits its delay; the venue's host has none.
        clock.waits shouldContainExactly listOf(5.seconds)
    }

    @Test
    fun `turns a 303 into a GET`() {
        venue.routes["/form"] = redirect(303, "/done")
        venue.routes["/done"] = page("done")

        client()
            .method(HttpMethod.POST)
            .uri(venue.url("/form"))
            .bodyValue("a=1")
            .retrieve()
            .bodyToMono<String>()
            .block() shouldBe "done"

        venue.requests.single { it.target == "/done" }.method shouldBe "GET"
    }

    @Test
    fun `logs a hop to another host with both hosts and the run's source, and does not log a same-host hop`() {
        venue.routes["/p"] = redirect(301, "/q")
        venue.routes["/q"] = redirect(302, other.url("/shop"))
        other.routes["/shop"] = page("shop")
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger(RedirectFollowingFilter::class.java) as Logger
        logger.addAppender(appender)

        try {
            MDC.put(LogContext.SOURCE_SLUG, "venue")
            get(client(), venue.url("/p"))
        } finally {
            MDC.remove(LogContext.SOURCE_SLUG)
            logger.detachAppender(appender)
        }

        val line = appender.list.single()
        line.level shouldBe Level.INFO
        line.formattedMessage shouldBe "Following a 302 from 127.0.0.1 to localhost"
        line.mdcPropertyMap[LogContext.SOURCE_SLUG] shouldBe "venue"
        line.keyValuePairs.associate { it.key to it.value }[LogFields.URL] shouldBe venue.url("/q")
    }

    @Test
    fun `does not carry the venue's cookie to another host`() {
        venue.routes["/p"] = redirect(302, other.url("/shop"))
        other.routes["/shop"] = page("shop")

        client()
            .get()
            .uri(venue.url("/p"))
            .cookie("age_ok", "1")
            .retrieve()
            .bodyToMono<String>()
            .block()

        venue.requests.single { it.target == "/p" }.headers["Cookie"] shouldBe "age_ok=1"
        other.requests.filter { it.headers["Cookie"] != null }.shouldBeEmpty()
    }

    private fun client(throttle: PerHostThrottlingFilter = config.perHostThrottlingFilter(properties)): WebClient {
        provider = config.scraperConnectionProvider(properties)
        val rules =
            RobotsRulesCache(
                webClient = config.scraperBaseWebClient(WebClient.builder(), properties, provider, throttle, { null }, Dispatchers.IO),
                scraperProperties = properties
            )
        return config.scraperWebClient(WebClient.builder(), properties, provider, throttle, rules, guard(), Dispatchers.IO)
    }

    /** The real guard, except that it lets the two servers' ports through and resolves [PRIVATE_HOST] to `10.1.1.10`. */
    private fun guard() =
        object : ImageUrlValidator() {
            override fun reject(url: String): String? = if (URI(url).port in setOf(venue.server.port, other.server.port)) null else super.reject(url)

            override fun resolve(host: String): Array<InetAddress> =
                if (host == PRIVATE_HOST) arrayOf(InetAddress.getByAddress(host, byteArrayOf(10, 1, 1, 10))) else super.resolve(host)
        }

    private fun get(
        client: WebClient,
        url: String
    ): String? =
        client
            .get()
            .uri(url)
            .retrieve()
            .bodyToMono<String>()
            .block()

    private fun redirect(
        status: Int,
        location: String
    ) = MockResponse
        .Builder()
        .code(status)
        .addHeader("Location", location)
        .build()

    private fun page(body: String) =
        MockResponse
            .Builder()
            .code(200)
            .body(body)
            .build()

    /** One server answering under one host name, from [routes], with an allow-all `robots.txt` unless [robots] says otherwise. */
    private class Host(
        private val name: String
    ) {
        val routes = mutableMapOf<String, MockResponse>()
        var robots = "User-agent: *\nAllow: /\n"
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val paths: List<String> get() = requests.map { it.target }

        val server =
            MockWebServer().apply {
                dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            requests += request
                            return if (request.target == "/robots.txt") {
                                MockResponse
                                    .Builder()
                                    .code(200)
                                    .body(robots)
                                    .build()
                            } else {
                                routes[request.target] ?: MockResponse.Builder().code(404).build()
                            }
                        }
                    }
                start(InetAddress.getByName("127.0.0.1"), 0)
            }

        fun url(path: String) = "http://$name:${server.port}$path"
    }

    /** Virtual time, as in [PerHostThrottlingFilterTest]: [wait] records the duration and advances the clock by it. */
    private class VirtualClock : ThrottleClock {
        private val source = TestTimeSource()
        val waits = mutableListOf<Duration>()

        override fun markNow(): TimeMark = source.markNow()

        override suspend fun wait(duration: Duration) {
            waits += duration
            source += duration
        }
    }

    private companion object {
        const val MAX = RedirectFollowingFilter.MAX_HOPS
        const val PRIVATE_HOST = "intranet.test"
    }
}
