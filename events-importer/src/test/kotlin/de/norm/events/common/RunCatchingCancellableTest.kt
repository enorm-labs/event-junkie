package de.norm.events.common

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import ch.qos.logback.classic.Level as LogbackLevel

private val testLogger = KotlinLogging.logger {}

class RunCatchingCancellableTest {
    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var root: Logger

    @BeforeEach
    fun attachAppender() {
        root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
    }

    @AfterEach
    fun detachAppender() {
        root.detachAppender(appender)
        appender.stop()
    }

    @Test
    fun `a value is a success`() {
        runCatchingCancellable { 42 } shouldBe Result.success(42)
    }

    @Test
    fun `an exception is a failure, as with runCatching`() {
        runCatchingCancellable { error("down") }.exceptionOrNull().shouldBeInstanceOf<IllegalStateException>()
    }

    @Test
    fun `a cancellation is rethrown, not caught`() {
        shouldThrow<CancellationException> { runCatchingCancellable { throw CancellationException("stop") } }
    }

    @Test
    fun `rethrowIfCancelled returns while the job is active`() =
        runTest {
            rethrowIfCancelled()
        }

    @Test
    fun `rethrowIfCancelled throws once the job is cancelled`() =
        runTest {
            var reachedAfter = false
            val job =
                launch {
                    cancel()
                    rethrowIfCancelled()
                    reachedAfter = true
                }
            job.join()
            reachedAfter shouldBe false
        }

    @Test
    fun `runScheduled returns the block's completion with nothing logged`() =
        runTest {
            var ran = false

            runScheduled("Orphan artist sweep", testLogger) { ran = true }

            ran shouldBe true
            appender.list.shouldBeEmpty()
        }

    @Test
    fun `runScheduled logs a failure as one ERROR line naming the pass, and swallows it`() =
        runTest {
            runScheduled("Orphan artist sweep", testLogger) { error("database gone") }

            val event = appender.list.single()
            event.level shouldBe LogbackLevel.ERROR
            event.formattedMessage shouldBe "Orphan artist sweep failed"
            event.throwableProxy.message shouldBe "database gone"
        }

    @Test
    fun `runScheduled rethrows a cancellation, so the schedule is not kept alive by it`() =
        runTest {
            var reachedAfter = false
            val job =
                launch {
                    cancel()
                    runScheduled("Orphan artist sweep", testLogger) { error("database gone") }
                    reachedAfter = true
                }
            job.join()
            reachedAfter shouldBe false
            appender.list.shouldBeEmpty()
        }
}
