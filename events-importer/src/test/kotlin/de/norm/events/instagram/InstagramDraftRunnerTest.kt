package de.norm.events.instagram

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
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

    private fun runner(maxPerRun: Int = 50) =
        InstagramDraftRunner(
            service = service,
            repository = repository,
            venues = venues,
            metrics = InstagramMetrics(registry),
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

    @Test
    fun `a pass deletes stale drafts, reads every venue with what is left of the cap, and counts`() =
        runTest {
            coEvery { venues.findVenues() } returns listOf(venue("prisma"), venue("rso.berlin"))
            coEvery { service.draftFrom(venue("prisma"), 50) } returns read(drafts = 2, calls = 30)
            coEvery { service.draftFrom(venue("rso.berlin"), 20) } returns read(drafts = 1, calls = 5)

            val result = runner().runDaily()

            result shouldBe InstagramPassResult(venues = 2, drafts = 3, calls = 35, removed = 2, stoppedBy = null)
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
    fun `the refused-access series exists at zero before any pass`() {
        InstagramMetrics(registry)
        registry
            .find("instagram.graph.errors")
            .tag("kind", "refused")
            .counter()!!
            .count() shouldBe 0.0
    }

    @Test
    fun `a refused access stops the pass with an error line, and an unreadable account does not`() =
        runTest {
            val log = LoggerFactory.getLogger(InstagramDraftRunner::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            log.addAppender(appender)
            try {
                coEvery { venues.findVenues() } returns listOf(venue("gone"), venue("prisma"), venue("rso.berlin"))
                coEvery { service.draftFrom(venue("gone"), any()) } returns VenueDraftResult.NotRead(DiscoveryResult.AccountUnreadable(110))
                coEvery { service.draftFrom(venue("prisma"), any()) } returns
                    VenueDraftResult.NotRead(DiscoveryResult.AccessRefused(190, "Session has expired"))

                runner().runDaily()?.stoppedBy shouldBe InstagramPassStop.ACCESS_REFUSED

                coVerify(exactly = 0) { service.draftFrom(venue("rso.berlin"), any()) }
                registry.counter("instagram.graph.errors", "kind", "refused").count() shouldBe 1.0
                registry.counter("instagram.graph.errors", "kind", "unreadable").count() shouldBe 1.0
                val errors = appender.list.filter { it.level == Level.ERROR }.map { it.formattedMessage }
                errors.first() shouldContain "Graph error 190"
                appender.list.single { it.level == Level.WARN }.formattedMessage shouldContain "'gone' was not read: Graph error 110"
            } finally {
                log.detachAppender(appender)
                appender.stop()
            }
        }
}
