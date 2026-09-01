package de.norm.events

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.reactor.mono
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
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

        assertEquals("req-42", seen.get())
        // Guards the guard: if the read happened on the writing thread, a plain MDC.put would also
        // have passed and the test would prove nothing.
        assertNotEquals(writer, readerThread.get())
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
        assertEquals("req-99", seen.get())
    }

    @Test
    fun `gives the access-log line a request id`() {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/venues?q=astra"))

        RequestLoggingFilter("/actuator").filter(exchange) { Mono.empty() }.block()

        val event = appender.list.single { it.formattedMessage.startsWith("GET /venues") }
        assertFalse(event.mdcPropertyMap[LogContextConfiguration.REQUEST_ID].isNullOrBlank())
    }

    @Test
    fun `gives two requests different ids`() {
        repeat(2) {
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/venues"))
            RequestLoggingFilter("/actuator").filter(exchange) { Mono.empty() }.block()
        }

        val ids = appender.list.mapNotNull { it.mdcPropertyMap[LogContextConfiguration.REQUEST_ID] }
        assertEquals(2, ids.size)
        assertEquals(2, ids.toSet().size)
    }
}
