package de.norm.events

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.reactor.mono
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import java.util.concurrent.atomic.AtomicReference

/**
 * The reactive half of #380, which is the half that fails **silently**.
 *
 * WebFlux runs one request across several threads and MDC is thread-local, so a value put in a
 * filter is absent by the time a handler logs — the line still appears, just without its fields.
 * Nothing throws, so only an assertion catches it. Each test below therefore reads the context back
 * from a thread that is deliberately not the one that wrote it.
 */
class LogContextPropagationTest {
    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var root: Logger

    @BeforeEach
    fun setUp() {
        // Registers the MDC accessor and enables automatic propagation, exactly as the running
        // application does. Both are process-wide and idempotent.
        LogContextConfiguration().enableRequestIdPropagation()
        root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
    }

    @AfterEach
    fun tearDown() {
        root.detachAppender(appender)
        appender.stop()
        MDC.clear()
    }

    @Test
    fun `restores the request id into MDC on a thread that never wrote it`() {
        val writer = Thread.currentThread().name
        val seen = AtomicReference<String?>()
        val readerThread = AtomicReference<String?>()

        Mono
            .just("payload")
            .publishOn(Schedulers.parallel())
            .doOnNext {
                seen.set(MDC.get(LogContextConfiguration.REQUEST_ID))
                readerThread.set(Thread.currentThread().name)
            }.contextWrite { it.put(LogContextConfiguration.REQUEST_ID, "req-42") }
            .block()

        seen.get() shouldBe "req-42"
        // Guards the guard: if the read happened on the writing thread, a plain MDC.put would also
        // have passed and the test would prove nothing.
        readerThread.get() shouldNotBe writer
    }

    @Test
    fun `does not reach a coroutine body, which is the boundary of what this buys`() {
        // **A negative assertion on purpose.** `Hooks.enableAutomaticContextPropagation` restores
        // the MDC around Reactor's own operator invocations; it does not reach inside a coroutine
        // started by the `mono { }` builder, which is how Spring invokes a `suspend` handler. So a
        // line logged in a suspend controller carries no `requestId` today.
        //
        // Written down as a test rather than a comment because the failure is invisible either way
        // — the log line still appears, just without the field — and because the fix, threading a
        // CoroutineContext through the Reactor context, is a change someone will make one day. On
        // that day this test fails and says exactly what changed, which a comment would not.
        val chain =
            mono { MDC.get(LogContextConfiguration.REQUEST_ID) }
                .contextWrite { it.put(LogContextConfiguration.REQUEST_ID, "req-99") }

        // `mono { }` completes empty when its block returns null, so an empty sequence *is* the
        // assertion that the MDC lookup found nothing.
        StepVerifier.create(chain).verifyComplete()
    }

    @Test
    fun `reaches an operator downstream of the handler, which is where the access log is written`() {
        val seen = AtomicReference<String?>()

        val chain =
            mono { "handled" }
                .publishOn(Schedulers.parallel())
                .doOnNext { seen.set(MDC.get(LogContextConfiguration.REQUEST_ID)) }
                .contextWrite { it.put(LogContextConfiguration.REQUEST_ID, "req-99") }

        StepVerifier.create(chain).expectNext("handled").verifyComplete()
        seen.get() shouldBe "req-99"
    }

    @Test
    fun `gives the access-log line a request id`() {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/venues?q=astra"))

        RequestLoggingFilter("/actuator").filter(exchange) { Mono.empty() }.block()

        // Found by its `path` field rather than by its text (#945). Matching on the message was
        // what this assertion did before the values became fields, and it is precisely the coupling
        // that made a wording change break an unrelated test.
        val event = appender.list.single { it.field(LogContextConfiguration.PATH) == "/venues" }
        event.mdcPropertyMap[LogContextConfiguration.REQUEST_ID].isNullOrBlank() shouldBe false
    }

    @Test
    fun `carries the method, path and status as fields rather than inside the sentence`() {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/venues?q=astra"))

        // The status is set by the chain, not by the mock — an unset one reads back as 0, which
        // would let the Int assertion below pass without proving the real value ever arrives.
        RequestLoggingFilter("/actuator")
            .filter(exchange) {
                exchange.response.statusCode = HttpStatus.OK
                Mono.empty()
            }.block()

        val event = appender.list.single()
        event.field(LogContextConfiguration.HTTP_METHOD) shouldBe "GET"
        event.field(LogContextConfiguration.PATH) shouldBe "/venues"
        // An Int, not "200". OpenObserve types a column from its first row, so one string here
        // would make every later range query on the status impossible.
        event.field(LogContextConfiguration.HTTP_STATUS) shouldBe 200

        // The other half of the same guarantee: a value that is a field AND still welded into the
        // sentence reads as working while leaving the prose to drift out of step with it.
        event.formattedMessage.startsWith("GET ") shouldBe false
    }

    @Test
    fun `keeps the query string out of the fields, which is a decision and not an oversight`() {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/venues?q=astra"))

        RequestLoggingFilter("/actuator").filter(exchange) { Mono.empty() }.block()

        val event = appender.list.single()
        // `q=astra` is user-typed input. It stays in the message, where it already was — making it
        // a filterable column is a different act, and LEGAL.md §7.5 says not to widen what request
        // data is collected without deciding to. Asserted so a later "tidy-up" has to choose it.
        event.formattedMessage.contains("?q=astra") shouldBe true
        event.field(LogContextConfiguration.PATH) shouldBe "/venues"
    }

    @Test
    fun `logs no access line at all for an actuator request`() {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/health"))

        RequestLoggingFilter("/actuator").filter(exchange) { Mono.empty() }.block()

        // The suppression predates this change; asserted here because the conversion rewrote the
        // line it guards and a silently-restored probe line is 1,437 rows an hour.
        (appender.list.none { it.field(LogContextConfiguration.PATH) != null }) shouldBe true
    }

    @Test
    fun `gives two requests different ids`() {
        repeat(2) {
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/venues"))
            RequestLoggingFilter("/actuator").filter(exchange) { Mono.empty() }.block()
        }

        val ids = appender.list.mapNotNull { it.mdcPropertyMap[LogContextConfiguration.REQUEST_ID] }
        ids.size shouldBe 2
        ids.toSet().size shouldBe 2
    }

    /**
     * The value SLF4J carries for [key] as a key-value pair, or `null` when the line has none.
     *
     * `keyValuePairs` is what `logger.at(…) { payload = … }` writes and what Spring's ECS formatter
     * serialises to the top level of the JSON — so this reads the same list the shipped log line is
     * built from, rather than a rendered string.
     */
    private fun ILoggingEvent.field(key: String): Any? = keyValuePairs?.firstOrNull { it.key == key }?.value
}
