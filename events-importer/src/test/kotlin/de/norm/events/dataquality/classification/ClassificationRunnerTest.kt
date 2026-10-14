package de.norm.events.dataquality.classification

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import de.norm.events.dataquality.ClassificationRunInProgressException
import de.norm.events.dataquality.DataQualityMetrics
import de.norm.events.dataquality.DataQualityRegressionCheck
import de.norm.events.importing.EventSourceEntity
import de.norm.events.importing.EventSourceRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/** The nightly pass and the background source run, against a mocked check (#2996). The check's own caps are in [EventClassificationCheckTest]. */
class ClassificationRunnerTest {
    private val check = mockk<EventClassificationCheck>()
    private val repository = mockk<EventClassificationRepository>()
    private val sources = mockk<EventSourceRepository> { coEvery { findBySlug(any()) } returns mockk<EventSourceEntity>() }
    private val metrics = DataQualityMetrics(SimpleMeterRegistry())
    private val ledger = mockk<ClassificationTokenLedger> { every { budget } returns BUDGET }

    private lateinit var appender: ListAppender<ILoggingEvent>
    private val logger = LoggerFactory.getLogger(ClassificationRunner::class.java) as Logger

    @BeforeEach
    fun setUp() {
        coEvery { ledger.monthTotal() } returns 0
        appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(appender)
        appender.stop()
    }

    @Test
    fun `sources with a regression go first, then the most candidates, and the slug breaks a tie`() {
        ClassificationRunner.order(mapOf("a" to 3L, "b" to 9L, "c" to 1L, "d" to 3L), regressed = setOf("c")) shouldContainExactly
            listOf("c", "b", "a", "d")
    }

    @Test
    fun `the pass reads the regression gauge, and logs its start and result`(): Unit =
        runBlocking {
            regress("quiet")
            candidates("busy" to 4, "quiet" to 1)
            checks("busy" to 4, "quiet" to 1)

            val result = runner().runNightly()

            result shouldBe NightlyPassResult(listOf("quiet", "busy"), calls = 5, stoppedBy = null)
            coVerifyOrder {
                check.checkSource("quiet", false, 50)
                check.checkSource("busy", false, 49)
            }
            info() shouldContainExactly
                listOf(
                    "Nightly event classification started: 2 source(s) with candidates, 1 with a data-quality regression first, " +
                        "at most 50 model call(s)",
                    "Nightly event classification finished: 2 of 2 source(s), 5 model call(s)"
                )
        }

    @Test
    fun `max-per-run caps the whole night, not one source`(): Unit =
        runBlocking {
            candidates("a" to 3, "b" to 4, "c" to 1)
            checks("a" to 3, "b" to 4, "c" to 1)

            val result = runner(maxPerRun = 5).runNightly()

            result shouldBe NightlyPassResult(listOf("b", "a"), calls = 5, stoppedBy = PassStop.NIGHT_CAP)
            coVerify { check.checkSource("b", false, 5) }
            coVerify { check.checkSource("a", false, 1) }
            coVerify(exactly = 0) { check.checkSource("c", any(), any()) }
            info().last() shouldBe "Nightly event classification reached max-per-run: 2 of 3 source(s), 5 model call(s); the rest wait for the next night"
        }

    @Test
    fun `the monthly token budget stops the pass at the source that reached it`(): Unit =
        runBlocking {
            candidates("a" to 5, "b" to 2)
            coEvery { check.checkSource("a", false, any()) } returns ClassificationRunResult(5, 3, 0, 0, 0, emptyMap(), overBudget = 2)

            val result = runner().runNightly()

            result shouldBe NightlyPassResult(listOf("a"), calls = 3, stoppedBy = PassStop.TOKEN_BUDGET)
            coVerify(exactly = 0) { check.checkSource("b", any(), any()) }
            warnings() shouldContainExactly
                listOf("Nightly event classification stopped at the monthly token budget of $BUDGET: 1 of 2 source(s), 3 model call(s)")
        }

    @Test
    fun `a month whose budget is spent runs no pass at all`(): Unit =
        runBlocking {
            coEvery { ledger.monthTotal() } returns BUDGET

            runner().runNightly().shouldBeNull()

            coVerify(exactly = 0) { repository.countSourceCandidates() }
            warnings() shouldContainExactly listOf("Nightly event classification skipped: $BUDGET of $BUDGET tokens used this month")
        }

    @Test
    fun `an exception never leaves the scheduled method, and the next night runs`(): Unit =
        runBlocking {
            candidates("a" to 1)
            coEvery { check.checkSource("a", false, any()) } throws IllegalStateException("database gone") andThen
                ClassificationRunResult(1, 1, 0, 0, 0, emptyMap())
            val runner = runner()

            runner.nightly()

            val error = appender.list.single { it.level == Level.ERROR }
            error.formattedMessage shouldBe "Nightly event classification failed"
            error.throwableProxy.message shouldBe "database gone"

            runner.nightly()

            coVerify(exactly = 2) { check.checkSource("a", false, any()) }
            info().last() shouldBe "Nightly event classification finished: 1 of 1 source(s), 1 model call(s)"
        }

    @Test
    fun `while a source run holds the runner, a second start is refused and the nightly pass is skipped`(): Unit =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            coEvery { check.checkSource("a", false, any()) } coAnswers {
                gate.await()
                ClassificationRunResult(1, 1, 0, 0, 0, emptyMap())
            }
            val runner = runner()

            val job = runner.startSource("a", force = false)

            shouldThrow<ClassificationRunInProgressException> { runner.startSource("b", force = false) }
            runner.runNightly().shouldBeNull()
            warnings() shouldContainExactly listOf("Nightly event classification skipped: another run is going")

            gate.complete(Unit)
            job.join()
            candidates()
            runner.runNightly() shouldBe NightlyPassResult(emptyList(), calls = 0, stoppedBy = null)
        }

    @Test
    fun `a background source run that fails is logged with the exception, and frees the runner`(): Unit =
        runBlocking {
            coEvery { check.checkSource("a", true, any()) } throws IllegalStateException("model gone")
            candidates()
            val runner = runner()

            runner.startSource("a", force = true).join()

            val error = appender.list.single { it.level == Level.ERROR }
            error.formattedMessage shouldBe "Background event classification of source 'a' failed"
            error.throwableProxy.message shouldBe "model gone"
            runner.runNightly() shouldBe NightlyPassResult(emptyList(), calls = 0, stoppedBy = null)
        }

    private fun runner(maxPerRun: Int = 50) =
        ClassificationRunner(
            check,
            repository,
            sources,
            metrics,
            ledger,
            ClassificationProperties(enabled = true, maxPerRun = maxPerRun),
            Dispatchers.IO
        )

    private fun candidates(vararg counts: Pair<String, Int>) {
        coEvery { repository.countSourceCandidates() } returns counts.associate { (slug, n) -> slug to n.toLong() }
    }

    /** Each source's run sends all its candidates, up to the calls it is given. */
    private fun checks(vararg counts: Pair<String, Int>) {
        counts.forEach { (slug, n) ->
            coEvery { check.checkSource(slug, false, any()) } answers {
                val calls = minOf(n, thirdArg<Int>())
                ClassificationRunResult(n, calls, 0, 0, n - calls, emptyMap())
            }
        }
    }

    private fun regress(source: String) =
        metrics.publishRegressions(
            listOf(DataQualityRegressionCheck.Verdict(source, "missingPrice", todayPct = 40.0, baselinePct = 5.0, totalEvents = 30))
        )

    private fun info() = appender.list.filter { it.level == Level.INFO }.map { it.formattedMessage }

    private fun warnings() = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }

    private companion object {
        const val BUDGET = 1_000L
    }
}
