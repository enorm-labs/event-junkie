package de.norm.events.scraper

import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * The meters the importer publishes.
 *
 * These assert **names and tag values as literal strings**, which is deliberate and is the reason
 * the file is worth its length. The alert rules and dashboards in `docs/ops/PLATFORM_SETUP.md` §7
 * are written against those exact strings and there is no compiler between the two — a rename that
 * looks like a tidy-up is a silently broken alert, and the single most valuable rule in the system
 * (a source that has quietly stopped importing) depends on it. Referring to the constants here
 * instead would make the test pass through any rename, which is precisely the change it exists to
 * catch.
 */
class ImporterMetricsTest {
    private lateinit var registry: SimpleMeterRegistry
    private lateinit var metrics: ImporterMetrics

    @BeforeEach
    fun setUp() {
        registry = SimpleMeterRegistry()
        metrics = ImporterMetrics(registry)
    }

    @Nested
    inner class RunOutcomes {
        @Test
        fun `a run records its duration and its outcome, tagged by source`() {
            metrics.recordRun("cassiopeia", ImporterMetrics.RunOutcome.SUCCESS, 250.milliseconds)

            val timer = registry.find("importer.run.duration").tag("source", "cassiopeia").timer()
            timer!!.count() shouldBe 1L
            timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) shouldBe 250.0

            registry
                .find("importer.run.outcome")
                .tags("source", "cassiopeia", "outcome", "success")
                .counter()!!
                .count() shouldBe 1.0
        }

        @Test
        fun `each outcome gets its own series, so they cannot be confused on a dashboard`() {
            metrics.recordRun("a", ImporterMetrics.RunOutcome.SUCCESS, 1.milliseconds)
            metrics.recordRun("a", ImporterMetrics.RunOutcome.FAILED, 1.milliseconds)
            metrics.recordRun("a", ImporterMetrics.RunOutcome.NOT_MODIFIED, 1.milliseconds)
            metrics.recordRun("a", ImporterMetrics.RunOutcome.MISCONFIGURED, 1.milliseconds)
            metrics.recordRun("a", ImporterMetrics.RunOutcome.SKIPPED, 1.milliseconds)

            listOf("success", "failed", "not_modified", "misconfigured", "skipped").forEach { outcome ->
                registry
                    .find("importer.run.outcome")
                    .tags("source", "a", "outcome", outcome)
                    .counter()!!
                    .count() shouldBe 1.0
            }
        }

        /**
         * A successful run is what makes `last_success` move. Without this the alert
         * "no success in 3× the interval" would only ever be fed by the periodic database refresh,
         * and a source that succeeded seconds ago would still look stale for up to a minute.
         */
        @Test
        fun `a successful run publishes last_success, and a failed one does not`() {
            metrics.recordRun("succeeds", ImporterMetrics.RunOutcome.SUCCESS, 1.milliseconds)
            metrics.recordRun("fails", ImporterMetrics.RunOutcome.FAILED, 1.milliseconds)

            registry
                .find("importer.source.last_success")
                .tag("source", "succeeds")
                .gauge()!!
                .value() shouldBeGreaterThan 0.0
            registry.find("importer.source.last_success").tag("source", "fails").gauge() shouldBe null
        }

        /**
         * The in-memory half of #618's invariant. [MetricsRefreshService] publishes `has_succeeded`
         * from the database once a minute; a run that succeeds in between must not leave the two
         * gauges disagreeing until the next tick, so a published last-success sets this too.
         *
         * The failed run is asserted as *absent* rather than 0 on purpose: `recordRun` knows this run
         * failed, not that the source has never succeeded. Only the database knows that, which is why
         * the zero comes from the refresh service and not from here.
         */
        @Test
        fun `a successful run also marks the source as having succeeded`() {
            metrics.recordRun("succeeds", ImporterMetrics.RunOutcome.SUCCESS, 1.milliseconds)
            metrics.recordRun("fails", ImporterMetrics.RunOutcome.FAILED, 1.milliseconds)

            registry
                .find("importer.source.has_succeeded")
                .tag("source", "succeeds")
                .gauge()!!
                .value() shouldBe 1.0
            registry.find("importer.source.has_succeeded").tag("source", "fails").gauge() shouldBe null
        }

        /**
         * A 304 is a working scraper — the request went out and the venue answered — so it advances
         * last-success, and this gauge has to agree with that rather than with intuition about the
         * word "success".
         */
        @Test
        fun `not_modified counts as having succeeded, because the scraper worked`() {
            metrics.recordRun("quiet-venue", ImporterMetrics.RunOutcome.NOT_MODIFIED, 1.milliseconds)

            registry
                .find("importer.source.has_succeeded")
                .tag("source", "quiet-venue")
                .gauge()!!
                .value() shouldBe 1.0
        }

        @Test
        fun `two runs of one source share a timer rather than creating a second series`() {
            metrics.recordRun("cassiopeia", ImporterMetrics.RunOutcome.SUCCESS, 100.milliseconds)
            metrics.recordRun("cassiopeia", ImporterMetrics.RunOutcome.SUCCESS, 300.milliseconds)

            registry
                .find("importer.run.duration")
                .tag("source", "cassiopeia")
                .timers()
                .size shouldBe 1
            registry
                .find("importer.run.duration")
                .tag("source", "cassiopeia")
                .timer()!!
                .count() shouldBe 2L
        }
    }

    @Nested
    inner class EventsWritten {
        @Test
        fun `writes are split by operation`() {
            metrics.recordEventsWritten("lido", ImporterMetrics.WriteOperation.INSERTED, 3)
            metrics.recordEventsWritten("lido", ImporterMetrics.WriteOperation.UPDATED, 2)
            metrics.recordEventsWritten("lido", ImporterMetrics.WriteOperation.SKIPPED, 7)

            fun count(operation: String) =
                registry
                    .find("importer.events.written")
                    .tags("source", "lido", "operation", operation)
                    .counter()!!
                    .count()

            count("inserted") shouldBe 3.0
            count("updated") shouldBe 2.0
            count("skipped") shouldBe 7.0
        }

        /**
         * A counter that is incremented by zero still creates the series, which would make
         * "this source has an inserted counter" meaningless. The interesting zero on this meter is
         * expressed by the counter *not advancing*, not by a zero increment.
         */
        @Test
        fun `a zero count creates no series at all`() {
            metrics.recordEventsWritten("quiet", ImporterMetrics.WriteOperation.INSERTED, 0)

            registry.find("importer.events.written").tag("source", "quiet").counter() shouldBe null
        }
    }

    @Nested
    inner class EventsDropped {
        @Test
        fun `drops are split by reason, because the three want different responses`() {
            metrics.recordUpsertOutcome(
                "lido",
                UpsertOutcome(inserted = 0, updated = 0, skipped = 0, droppedPast = 4, droppedDuplicate = 2),
                droppedUnresolvedDate = 1
            )

            fun count(reason: String) =
                registry
                    .find("importer.events.dropped")
                    .tags("source", "lido", "reason", reason)
                    .counter()!!
                    .count()

            // `past` is the scraper working — a calendar source republishing last month. The other
            // two are data we could have had: one is a scraper keying on the show rather than the
            // session (#333), the other a page that published no date. Summed, they are one number
            // that means nothing.
            count("past") shouldBe 4.0
            count("duplicate") shouldBe 2.0
            count("unresolved_date") shouldBe 1.0
        }

        @Test
        fun `a zero count creates no series at all`() {
            metrics.recordUpsertOutcome("quiet", UpsertOutcome(inserted = 0, updated = 0, skipped = 0), droppedUnresolvedDate = 0)

            registry.find("importer.events.dropped").tag("source", "quiet").counter() shouldBe null
        }

        /**
         * The cardinality rule, asserted rather than only written down. `scrapeFailureReason`'s KDoc
         * is where it is argued: a tag fed by a title or a URL is unbounded, and exhausting the
         * metrics backend reads as slow monitoring rather than as a bug here.
         */
        @Test
        fun `every reason tag is a constant from the enum`() {
            ImporterMetrics.DropReason.entries.forEach { it.tag shouldBe it.tag.lowercase() }
            ImporterMetrics.DropReason.entries
                .map { it.tag }
                .toSet()
                .size shouldBe
                ImporterMetrics.DropReason.entries.size
        }
    }

    @Nested
    inner class ScrapeFailures {
        @Test
        fun `failures are tagged with their reason so a block and a parse error are distinguishable`() {
            metrics.recordScrapeFailure("berghain", "http_forbidden")
            metrics.recordScrapeFailure("berghain", "parse")
            metrics.recordScrapeFailure("berghain", "parse")

            registry
                .find("importer.scrape.failures")
                .tags("source", "berghain", "reason", "http_forbidden")
                .counter()!!
                .count() shouldBe 1.0
            registry
                .find("importer.scrape.failures")
                .tags("source", "berghain", "reason", "parse")
                .counter()!!
                .count() shouldBe 2.0
        }
    }

    @Nested
    inner class Gauges {
        @Test
        fun `the polled gauges exist from construction, before anything has refreshed them`() {
            registry.find("importer.source.running").gauge()!!.value() shouldBe 0.0
            registry
                .find("db.events")
                .tag("horizon", "all")
                .gauge()!!
                .value() shouldBe 0.0
            registry
                .find("db.events")
                .tag("horizon", "future")
                .gauge()!!
                .value() shouldBe 0.0
        }

        @Test
        fun `refreshing updates them in place rather than registering a second gauge`() {
            metrics.updateEventCounts(total = 2965, future = 1200)
            metrics.updateSourcesRunning(2)
            metrics.updateEventCounts(total = 3000, future = 1250)

            registry
                .find("db.events")
                .tag("horizon", "all")
                .gauges()
                .size shouldBe 1
            registry
                .find("db.events")
                .tag("horizon", "all")
                .gauge()!!
                .value() shouldBe 3000.0
            registry
                .find("db.events")
                .tag("horizon", "future")
                .gauge()!!
                .value() shouldBe 1250.0
            registry.find("importer.source.running").gauge()!!.value() shouldBe 2.0
        }

        /**
         * A **timestamp**, not an age — so the alert can say
         * `time() - importer_source_last_success_seconds > 3 * interval` and stay true between
         * scrapes, which an age computed at record time cannot.
         */
        @Test
        fun `last_success is published per source as an epoch second`() {
            metrics.publishLastSuccess("a", 1_780_000_000L)
            metrics.publishLastSuccess("b", 1_780_000_500L)
            metrics.publishLastSuccess("a", 1_780_000_900L)

            registry
                .find("importer.source.last_success")
                .tag("source", "a")
                .gauges()
                .size shouldBe 1
            registry
                .find("importer.source.last_success")
                .tag("source", "a")
                .gauge()!!
                .value() shouldBe 1_780_000_900.0
            registry
                .find("importer.source.last_success")
                .tag("source", "b")
                .gauge()!!
                .value() shouldBe 1_780_000_500.0
        }

        /**
         * One series per source, updated in place — and **zero is a value**, not a reason to skip
         * publishing (#700). A source missing from the exposition cannot be alerted on and reads as
         * healthy, which is precisely the source this gauge exists to catch.
         */
        @Test
        fun `events_future is published per source, and a zero is published like any other number`() {
            metrics.publishFutureEvents("busy", 41)
            metrics.publishFutureEvents("emptied-out", 0)
            metrics.publishFutureEvents("busy", 38)

            registry
                .find("importer.source.events_future")
                .tag("source", "busy")
                .gauges()
                .size shouldBe 1
            registry
                .find("importer.source.events_future")
                .tag("source", "busy")
                .gauge()!!
                .value() shouldBe 38.0
            registry
                .find("importer.source.events_future")
                .tag("source", "emptied-out")
                .gauge()!!
                .value() shouldBe 0.0
        }
    }
}
