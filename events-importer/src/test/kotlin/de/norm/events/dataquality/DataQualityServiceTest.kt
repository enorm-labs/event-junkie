package de.norm.events.dataquality

import de.norm.events.scraper.EventSourceEntity
import de.norm.events.scraper.EventSourceRepository
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The arithmetic, in isolation from the database.
 *
 * `DataQualityReportIntegrationTest` proves the SQL and the column mapping; this proves what the
 * service does with the rows, which is the half a Testcontainers test makes slow and awkward to
 * cover exhaustively — rounding, empty denominators, and the roll-up.
 */
class DataQualityServiceTest {
    private val repository: DataQualityRepository = mockk()
    private val worklistRepository: DataQualityWorklistRepository = mockk()
    private val sources: EventSourceRepository = mockk()
    private val clock = Clock.fixed(Instant.parse("2026-08-19T10:15:00Z"), ZoneOffset.UTC)
    private lateinit var service: DataQualityService

    @BeforeEach
    fun setUp() {
        service = DataQualityService(repository, worklistRepository, sources, clock)
        coEvery { repository.artistNamesPerSource() } returns emptyFlow()
        coEvery { sources.findAll() } returns
            listOf(source(1L, "alpha"), source(2L, "beta")).asFlow()
    }

    private fun source(
        id: Long,
        slug: String
    ) = EventSourceEntity(
        id = id,
        venueId = 1L,
        name = slug,
        slug = slug,
        url = "https://$slug.example",
        sourceType = "CASSIOPEIA"
    )

    private fun row(
        sourceId: Long?,
        total: Long,
        concerts: Long = 0
    ) = SourceQualityRow(
        eventSourceId = sourceId,
        totalEvents = total,
        concertsWithoutArtist = concerts,
        eventsTypedOther = 0,
        missingGenre = 0,
        missingPromoter = 0,
        missingPrice = 0,
        missingStartTime = 0
    )

    @Test
    fun `percentages are rounded to one decimal, because that is what a human scans`() =
        runTest {
            coEvery { repository.aggregatePerSource() } returns listOf(row(1L, total = 92, concerts = 72)).asFlow()

            service
                .report()
                .perSource
                .single()
                .concertsWithoutArtistPct shouldBe 78.3
        }

    /**
     * A source with no events yet is a real state — one added this morning that has not run. `0 / 0`
     * is `NaN`, and `NaN` serialises to JSON as something no two clients agree on.
     */
    @Test
    fun `an empty source reports zero rather than NaN`() =
        runTest {
            coEvery { repository.aggregatePerSource() } returns listOf(row(1L, total = 0)).asFlow()

            val metrics = service.report().perSource.single()
            metrics.concertsWithoutArtistPct shouldBe 0.0
            metrics.missingGenrePct shouldBe 0.0
        }

    /**
     * The roll-up is summed from the per-source rows and never queried again — two queries moments
     * apart against a table the importer is writing to would disagree, intermittently and slightly,
     * which is the kind of thing that quietly costs a dashboard its credibility.
     */
    @Test
    fun `overall sums the sources and recomputes its own percentage from the totals`() =
        runTest {
            coEvery { repository.aggregatePerSource() } returns
                listOf(row(1L, total = 100, concerts = 50), row(2L, total = 100, concerts = 10)).asFlow()

            val overall = service.report().overall
            overall.source shouldBe DataQualityService.OVERALL
            overall.totalEvents shouldBe 200L
            overall.concertsWithoutArtist shouldBe 60L
            // 60/200, not the mean of 50% and 10% — a per-source average would weight a source with
            // three events the same as one with three hundred.
            overall.concertsWithoutArtistPct shouldBe 30.0
        }

    @Test
    fun `events with no source land in the manual bucket rather than being dropped`() =
        runTest {
            coEvery { repository.aggregatePerSource() } returns
                listOf(row(1L, total = 3), row(null, total = 2)).asFlow()

            val report = service.report()
            report.perSource.map { it.source } shouldBe listOf("alpha", "manual")
            report.overall.totalEvents shouldBe 5L
        }

    /**
     * A source id that no longer resolves is reported under its id rather than dropped. It should
     * not happen — `event.event_source_id` is `ON DELETE SET NULL` — but "should not happen" is how
     * rows vanish from a report silently, and a report that loses events is worse than an ugly label.
     */
    @Test
    fun `an unresolvable source id is still reported, under a label that says so`() =
        runTest {
            coEvery { repository.aggregatePerSource() } returns listOf(row(99L, total = 4)).asFlow()

            val report = service.report()
            // NOT "manual": folding it in there would attribute a deleted source's events to hand
            // curation, and nobody would ever question that number.
            report.perSource.single().source shouldBe "unresolved-source-99"
            report.overall.totalEvents shouldBe 4L
        }

    /**
     * The Kotlin-side metric. `TBA` and `Support` are in the curated vocabulary; `Die Nerven` is not.
     * Counted per source, and distinct — one bad name on forty events is one thing to fix.
     */
    @Test
    fun `suspect artist names are filtered by the curated vocabulary, per source`() =
        runTest {
            coEvery { repository.aggregatePerSource() } returns
                listOf(row(1L, total = 10), row(2L, total = 10)).asFlow()
            coEvery { repository.artistNamesPerSource() } returns
                listOf(
                    SourceArtistNameRow(1L, "TBA"),
                    SourceArtistNameRow(1L, "Support"),
                    SourceArtistNameRow(1L, "Die Nerven"),
                    SourceArtistNameRow(2L, "Sleaford Mods")
                ).asFlow()

            val bySource = service.report().perSource.associateBy { it.source }
            bySource.getValue("alpha").suspectNonArtistTitles shouldBe 2L
            bySource.getValue("beta").suspectNonArtistTitles shouldBe 0L
        }
}
