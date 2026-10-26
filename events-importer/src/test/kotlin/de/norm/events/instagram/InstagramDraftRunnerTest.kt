package de.norm.events.instagram

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class InstagramDraftRunnerTest {
    private val service = mockk<InstagramDraftService>()
    private val repository = mockk<InstagramDraftRepository>()
    private val venues = mockk<InstagramVenueRepository>()
    private val registry = SimpleMeterRegistry()
    private val clock = Clock.fixed(Instant.parse("2026-10-15T04:30:00Z"), ZoneOffset.UTC)
    private val metrics = InstagramMetrics(registry)

    private fun runner(
        maxPerRun: Int = 50,
        clock: Clock = this.clock
    ) = InstagramDraftRunner(
        service = service,
        repository = repository,
        venues = venues,
        metrics = metrics,
        properties = InstagramProperties(enabled = true, maxPerRun = maxPerRun),
        ioDispatcher = Dispatchers.IO,
        clock = clock
    )

    private fun venue(slug: String) = InstagramVenue(slug.length.toLong(), slug, slug, "https://www.instagram.com/$slug/")

    private fun read(
        drafts: Int,
        calls: Int
    ) = VenueDraftResult.Read(posts = 25, newPosts = calls, drafts = drafts, notEvents = 0, rejected = 0, failed = 1, calls = calls, tokens = 100)

    init {
        coEvery { repository.deletePendingCreatedBefore(any()) } returns 2
    }

    /** The runner's log lines while [block] runs. */
    private inline fun logsOf(block: () -> Unit): List<ILoggingEvent> {
        val log = LoggerFactory.getLogger(InstagramDraftRunner::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        log.addAppender(appender)
        try {
            block()
        } finally {
            log.detachAppender(appender)
            appender.stop()
        }
        return appender.list.toList()
    }

    @Test
    fun `a pass deletes stale drafts, reads every venue with what is left of the cap, and counts`() =
        runTest {
            coEvery { venues.findVenues() } returns listOf(venue("prisma"), venue("rso.berlin"))
            coEvery { service.draftFrom(venue("prisma"), 50) } returns read(drafts = 2, calls = 30)
            coEvery { service.draftFrom(venue("rso.berlin"), 20) } returns read(drafts = 1, calls = 5)

            val result = runner().runDaily()

            result shouldBe InstagramPassResult(venues = 2, notRead = 0, drafts = 3, calls = 35, removed = 2, stoppedBy = null)
            coVerify { repository.deletePendingCreatedBefore(OffsetDateTime.parse("2026-10-01T04:30:00Z")) }
            registry.counter("instagram.drafts").count() shouldBe 3.0
            registry.counter("instagram.posts.read").count() shouldBe 50.0
            registry.counter("instagram.extractions", "outcome", "failed").count() shouldBe 2.0
        }

    @Test
    fun `the cap stops the pass before the next venue`() =
        runTest {
            coEvery { venues.findVenues() } returns listOf(venue("prisma"), venue("rso.berlin"))
            coEvery { service.draftFrom(venue("prisma"), 10) } returns read(drafts = 4, calls = 10)

            runner(maxPerRun = 10).runDaily()?.stoppedBy shouldBe InstagramPassStop.CALL_CAP

            coVerify(exactly = 0) { service.draftFrom(venue("rso.berlin"), any()) }
        }

    @Test
    fun `a venue with no handle and an unavailable Graph are counted, and the pass goes on`() =
        runTest {
            coEvery { venues.findVenues() } returns listOf(venue("bare"), venue("prisma"), venue("rso.berlin"))
            coEvery { service.draftFrom(venue("bare"), any()) } returns VenueDraftResult.NoHandle
            coEvery { service.draftFrom(venue("prisma"), any()) } returns VenueDraftResult.NotRead(DiscoveryResult.Unavailable("HTTP 502"))
            coEvery { service.draftFrom(venue("rso.berlin"), any()) } returns read(drafts = 1, calls = 2)

            val logs =
                logsOf {
                    runner().runDaily() shouldBe InstagramPassResult(venues = 1, notRead = 2, drafts = 1, calls = 2, removed = 2, stoppedBy = null)
                }

            registry.counter("instagram.graph.errors", "kind", "unavailable").count() shouldBe 1.0
            val warnings = logs.filter { it.level == Level.WARN }.map { it.formattedMessage }
            warnings.first() shouldContain "'bare' is switched on, but its instagram_url names no profile"
            warnings.last() shouldContain "'prisma' was not read: HTTP 502"
        }

    @Test
    fun `a read by hand needs a switched-on venue, and nothing else starts while it runs`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            coEvery { venues.findVenues() } returns listOf(venue("prisma"))
            coEvery { service.draftFrom(venue("prisma"), 50) } coAnswers {
                gate.await()
                read(drafts = 1, calls = 1)
            }
            val runner = runner()

            shouldThrow<InstagramVenueNotFoundException> { runner.startVenue("rso.berlin") }
            val job = runner.startVenue("prisma")
            shouldThrow<InstagramRunInProgressException> { runner.startPass() }
            runner.runDaily().shouldBeNull()
            gate.complete(Unit)
            job.join()

            runner.runDaily().shouldNotBeNull()
            coVerify(exactly = 2) { service.draftFrom(venue("prisma"), 50) }
        }

    @Test
    fun `a run that fails in the background is logged and frees the lock`() =
        runTest {
            coEvery { venues.findVenues() } returns listOf(venue("prisma"))
            var calls = 0
            coEvery { service.draftFrom(venue("prisma"), any()) } coAnswers {
                if (calls++ == 0) error("Graph answered nonsense") else read(drafts = 0, calls = 1)
            }
            val runner = runner()

            val logs = logsOf { runner.startPass().join() }

            logs.single { it.level == Level.ERROR }.formattedMessage shouldBe "Instagram pass failed"
            runner.runDaily()?.venues shouldBe 1
        }

    @Test
    fun `the scheduled pass logs a failure instead of letting it escape, and runs again next time`() =
        runTest {
            coEvery { venues.findVenues() } returns emptyList()
            coEvery { repository.deletePendingCreatedBefore(any()) } throws IllegalStateException("database gone") andThen 0
            val runner = runner()

            val logs = logsOf { runner.daily() }

            logs.single { it.level == Level.ERROR }.formattedMessage shouldBe "The Instagram pass failed"
            runner.runDaily() shouldBe InstagramPassResult(venues = 0, notRead = 0, drafts = 0, calls = 0, removed = 0, stoppedBy = null)
        }

    @Test
    fun `shutting down cancels a read that is still going`() =
        runTest {
            coEvery { venues.findVenues() } returns listOf(venue("prisma"))
            coEvery { service.draftFrom(any(), any()) } coAnswers {
                CompletableDeferred<Unit>().await()
                VenueDraftResult.NoHandle
            }
            val runner = runner()
            val job = runner.startVenue("prisma")

            runner.destroy()
            job.join()

            job.isCancelled shouldBe true
        }

    @Test
    fun `the refused-access series exists at zero before any pass`() {
        InstagramMetrics(registry)
        registry
            .find("instagram.graph.errors")
            .tag("kind", "refused")
            .counter()!!
            .count() shouldBe 0.0
    }

    @Test
    fun `the age starts with the runner, moves when a pass ends, and stays after a refused access`() =
        runTest {
            val lastSuccess = registry.find(InstagramMetrics.LAST_SUCCESS).gauge()!!
            lastSuccess.value() shouldBe 0.0
            var now = clock.instant()
            val moving =
                mockk<Clock> {
                    every { instant() } answers { now }
                    every { zone } returns ZoneOffset.UTC
                }
            coEvery { venues.findVenues() } returns listOf(venue("prisma"))
            coEvery { service.draftFrom(venue("prisma"), any()) } returns read(drafts = 1, calls = 1)

            val runner = runner(clock = moving)
            lastSuccess.value() shouldBe now.epochSecond.toDouble()

            now = now.plusSeconds(3600)
            runner.runDaily()
            lastSuccess.value() shouldBe now.epochSecond.toDouble()

            val stamped = now
            coEvery { service.draftFrom(venue("prisma"), any()) } returns
                VenueDraftResult.NotRead(DiscoveryResult.AccessRefused(190, "Session has expired"))
            now = now.plusSeconds(86_400)
            runner.runDaily()
            lastSuccess.value() shouldBe stamped.epochSecond.toDouble()
        }

    @Test
    fun `a refused access stops the pass with an error line, and an unreadable account does not`() =
        runTest {
            coEvery { venues.findVenues() } returns listOf(venue("gone"), venue("prisma"), venue("rso.berlin"))
            coEvery { service.draftFrom(venue("gone"), any()) } returns VenueDraftResult.NotRead(DiscoveryResult.AccountUnreadable(110))
            coEvery { service.draftFrom(venue("prisma"), any()) } returns
                VenueDraftResult.NotRead(DiscoveryResult.AccessRefused(190, "Session has expired"))

            val logs = logsOf { runner().runDaily()?.stoppedBy shouldBe InstagramPassStop.ACCESS_REFUSED }

            coVerify(exactly = 0) { service.draftFrom(venue("rso.berlin"), any()) }
            registry.counter("instagram.graph.errors", "kind", "refused").count() shouldBe 1.0
            registry.counter("instagram.graph.errors", "kind", "unreadable").count() shouldBe 1.0
            val errors = logs.filter { it.level == Level.ERROR }.map { it.formattedMessage }
            errors.first() shouldContain "Graph error 190"
            logs.single { it.level == Level.WARN }.formattedMessage shouldContain "'gone' was not read: Graph error 110"
        }

    @ParameterizedTest
    @ValueSource(ints = [4, 17, 32, 613])
    fun `a rate limit stops the pass with one warning, and the summary counts the venues not read`(code: Int) =
        runTest {
            coEvery { venues.findVenues() } returns listOf(venue("prisma"), venue("ysy"), venue("villa"), venue("zemin"))
            coEvery { service.draftFrom(venue("prisma"), any()) } returns read(drafts = 1, calls = 2)
            coEvery { service.draftFrom(venue("ysy"), any()) } returns VenueDraftResult.NotRead(DiscoveryResult.RateLimited(code))

            val logs =
                logsOf {
                    runner().runDaily() shouldBe
                        InstagramPassResult(venues = 1, notRead = 3, drafts = 1, calls = 2, removed = 2, stoppedBy = InstagramPassStop.RATE_LIMIT)
                }

            coVerify(exactly = 0) { service.draftFrom(venue("villa"), any()) }
            coVerify(exactly = 0) { service.draftFrom(venue("zemin"), any()) }
            registry.counter("instagram.graph.errors", "kind", "rate_limited").count() shouldBe 1.0
            logs.single { it.level == Level.WARN }.formattedMessage shouldBe
                "Instagram venue 'ysy' was not read: Graph error $code, a rate limit. " +
                "The pass stops, and 3 venue(s) wait for the next pass, this one included"
            logs.last().formattedMessage shouldBe
                "The Instagram pass stopped at a rate limit: 1 of 4 venue(s) read, 3 not read, 1 draft(s), 2 model call(s); " +
                "the rest wait for the next pass"
        }
}
