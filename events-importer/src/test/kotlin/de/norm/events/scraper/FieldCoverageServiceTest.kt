package de.norm.events.scraper

import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * The rule, and the two guards that stop it being a noise generator.
 *
 * The headline case — **same event count, one selector broken** — is the one #472 exists for and the
 * one nothing else in this repository can catch: #415's alarm needs the count to go to zero, and it
 * does not.
 */
class FieldCoverageServiceTest {
    private val stats: ImportRunFieldStatsRepository = mockk(relaxed = true)
    private val sources: EventSourceRepository = mockk(relaxed = true)
    private val clock = Clock.fixed(Instant.parse("2026-08-19T04:00:00Z"), ZoneOffset.UTC)
    private lateinit var registry: SimpleMeterRegistry
    private lateinit var service: FieldCoverageService

    @BeforeEach
    fun setUp() {
        registry = SimpleMeterRegistry()
        service = FieldCoverageService(stats, sources, ImporterMetrics(registry), BASELINE_RUNS, MIN_SAMPLE, clock)
        coEvery { stats.findRecent(any(), any(), any()) } returns emptyFlow()
    }

    private fun source(flagged: Instant? = null) =
        EventSourceEntity(
            id = 1L,
            venueId = 1L,
            name = "alpha",
            slug = "alpha",
            url = "https://alpha.example/events",
            sourceType = "CASSIOPEIA",
            flaggedAt = flagged
        )

    /** [withGenre] of [count] events carry a genre; all of them carry everything else. */
    private fun events(
        count: Int,
        withGenre: Int = count
    ) = (1..count).map { i ->
        ScrapedEvent(
            title = "Show $i",
            eventDate = LocalDate.of(2026, 9, 1),
            sourceUrl = "https://alpha.example/$i",
            sourceId = "alpha:$i",
            genre = if (i <= withGenre) "Rock" else null,
            startTime = java.time.LocalTime.of(20, 0),
            pricePresale = java.math.BigDecimal("12.00"),
            artists = listOf(ScrapedArtist(name = "Band $i"))
        )
    }

    /** A history of [runs] runs at [ratio] coverage over [size] events each, newest first. */
    private fun history(
        runs: Int,
        ratio: Double,
        size: Int = 40
    ) = (1..runs).map {
        ImportRunFieldStatsEntity(
            id = it.toLong(),
            runId = UUID.randomUUID(),
            sourceId = 1L,
            field = TrackedField.GENRE.key,
            eventsTotal = size,
            eventsWithValue = (size * ratio).toInt(),
            // Descending, so the list is newest-first exactly as `findRecent` returns it. Built by
            // subtracting days rather than by string-formatting a number into an ISO literal, which
            // is how the eleventh run in a ten-run history became "2026-08-110".
            observedAt = BASE_INSTANT.minus(it.toLong(), java.time.temporal.ChronoUnit.DAYS)
        )
    }

    /**
     * **The case this whole feature exists for**: the venue moved the genre into a different
     * element, one selector stopped matching, and the importer wrote exactly as many events as it
     * always does. Nothing count-based sees it.
     */
    @Test
    fun `same event count, one field's selector broken, and it flags`() =
        runTest {
            // Ten runs at 100%, and the previous run already at 0% — the second guard's requirement.
            coEvery { stats.findRecent(1L, TrackedField.GENRE.key, any()) } returns
                (history(1, ratio = 0.0) + history(BASELINE_RUNS, ratio = 1.0)).asFlow()

            val findings = service.record(source(), events(count = 40, withGenre = 0))

            findings.map { it.field } shouldBe listOf(TrackedField.GENRE)
            findings.single().baseline shouldBe 1.0
            findings.single().observed shouldBe 0.0

            val reason = slot<String>()
            coVerify { sources.setFlag(eq(1L), any(), capture(reason)) }
            reason.captured shouldBe "genre 0% of 40 events, baseline 100%"
        }

    /**
     * The first guard. A run that scraped three events proves nothing about coverage, and comparing
     * it would make every quiet week a false alarm.
     */
    @Test
    fun `a run below the minimum sample size is recorded and never compared`() =
        runTest {
            coEvery { stats.findRecent(1L, TrackedField.GENRE.key, any()) } returns
                (history(1, ratio = 0.0) + history(BASELINE_RUNS, ratio = 1.0)).asFlow()

            service.record(source(), events(count = 3, withGenre = 0)) shouldBe emptyList()

            // Still recorded — the history is what the baseline is derived from, and dropping small
            // runs from it would bias the median towards busy weeks.
            coVerify(exactly = TrackedField.entries.size) { stats.save(any()) }
            coVerify(exactly = 0) { sources.setFlag(any(), any(), any()) }
        }

    /**
     * The second guard. A single run can legitimately skew — a week of club nights has no lineup and
     * no support act, and that is not a regression.
     */
    @Test
    fun `one run below baseline does not flag — the second guard needs two`() =
        runTest {
            coEvery { stats.findRecent(1L, TrackedField.GENRE.key, any()) } returns history(BASELINE_RUNS, ratio = 1.0).asFlow()

            service.record(source(), events(count = 40, withGenre = 0)) shouldBe emptyList()
            coVerify(exactly = 0) { sources.setFlag(any(), any(), any()) }
        }

    /**
     * The design decision that makes this work rather than annoy: the baseline is **per source and
     * derived from history**, so a venue that has never published a genre is never flagged for
     * missing one. A global expectation would flag half the corpus permanently.
     */
    @Test
    fun `a source that has never published a field is never flagged for missing it`() =
        runTest {
            coEvery { stats.findRecent(1L, TrackedField.GENRE.key, any()) } returns
                history(BASELINE_RUNS + 1, ratio = 0.0).asFlow()

            service.record(source(), events(count = 40, withGenre = 0)) shouldBe emptyList()
        }

    /**
     * The absolute floor. A field sitting at 8% that drifts to 3% is five events in a hundred and
     * almost certainly the venue rather than us — halving alone would fire on it.
     */
    @Test
    fun `a low-coverage field halving is not material, because the drop is small in absolute terms`() =
        runTest {
            coEvery { stats.findRecent(1L, TrackedField.GENRE.key, any()) } returns
                (history(1, ratio = 0.05) + history(BASELINE_RUNS, ratio = 0.15)).asFlow()

            service.record(source(), events(count = 40, withGenre = 2)) shouldBe emptyList()
        }

    /**
     * Not enough history is not a finding.
     *
     * Two prior runs would make the median the mean of two numbers, which is exactly the "one lucky
     * run is the standard" failure the median was chosen to avoid. Three is the floor, asserted at
     * the boundary in both directions by this test and the one below it.
     */
    @Test
    fun `a source with two prior runs has no baseline yet and cannot be flagged`() =
        runTest {
            coEvery { stats.findRecent(1L, TrackedField.GENRE.key, any()) } returns
                (history(1, ratio = 0.0) + history(1, ratio = 1.0)).asFlow()

            service.record(source(), events(count = 40, withGenre = 0)) shouldBe emptyList()
        }

    /** The other side of the same boundary: with three, there is a baseline and it bites. */
    @Test
    fun `three prior runs are enough for a baseline`() =
        runTest {
            coEvery { stats.findRecent(1L, TrackedField.GENRE.key, any()) } returns
                (history(1, ratio = 0.0) + history(2, ratio = 1.0)).asFlow()

            service.record(source(), events(count = 40, withGenre = 0)).map { it.field } shouldBe
                listOf(TrackedField.GENRE)
        }

    /**
     * A flag that is only ever set is permanently on within a month, and then it is decoration.
     */
    @Test
    fun `a normal run clears an existing flag`() =
        runTest {
            coEvery { stats.findRecent(1L, TrackedField.GENRE.key, any()) } returns history(BASELINE_RUNS, ratio = 1.0).asFlow()

            service.record(source(flagged = Instant.parse("2026-08-18T04:00:00Z")), events(count = 40))

            coVerify { sources.clearFlag(1L) }
        }

    /** ...and a source that was not flagged is not written to on every single run. */
    @Test
    fun `a normal run on an unflagged source writes nothing to the source row`() =
        runTest {
            coEvery { stats.findRecent(1L, TrackedField.GENRE.key, any()) } returns history(BASELINE_RUNS, ratio = 1.0).asFlow()

            service.record(source(), events(count = 40))

            coVerify(exactly = 0) { sources.clearFlag(any()) }
            coVerify(exactly = 0) { sources.setFlag(any(), any(), any()) }
        }

    /**
     * Zero events is #415's alarm, not this one. A row of all-zero coverage would drag every
     * baseline down and make the next real run look like the regression.
     */
    @Test
    fun `a run that scraped nothing records nothing, because that is a different alarm`() =
        runTest {
            service.record(source(), emptyList()) shouldBe emptyList()

            coVerify(exactly = 0) { stats.save(any()) }
        }

    /**
     * **A measurement that can fail an import is worse than no measurement.**
     *
     * This runs after the upsert has already committed, so a throw would report a failed run that
     * wrote every event, consume retry budget, and back the source off — the measurement costing
     * exactly the thing it exists to observe. The promise lives here rather than at the call site so
     * the next caller gets it without remembering to, which is why it is asserted here.
     */
    @Test
    fun `a repository failure is swallowed, because measurement must not fail an import`() =
        runTest {
            coEvery { stats.save(any()) } throws IllegalStateException("stats table is gone")

            service.record(source(), events(count = 40)) shouldBe emptyList()
        }

    @Test
    fun `every tracked field publishes a coverage gauge`() =
        runTest {
            service.record(source(), events(count = 40, withGenre = 10))

            registry
                .find(ImporterMetrics.FIELD_COVERAGE)
                .tags(ImporterMetrics.TAG_SOURCE, "alpha", ImporterMetrics.TAG_FIELD, "genre")
                .gauge()!!
                .value() shouldBe 0.25
            registry.find(ImporterMetrics.FIELD_COVERAGE).gauges().size shouldBe TrackedField.entries.size
        }

    private companion object {
        val BASE_INSTANT: Instant = Instant.parse("2026-08-19T04:00:00Z")
        const val BASELINE_RUNS = 10
        const val MIN_SAMPLE = 10
    }
}
