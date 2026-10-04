package de.norm.events.scraper.berghain

import de.norm.events.event.EventEntity
import de.norm.events.event.EventRepository
import de.norm.events.scraper.AssociationSyncService
import de.norm.events.scraper.EventUpsertService
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.PerformerTyping
import de.norm.events.scraper.StaleCleanup
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * A Halle exhibition through the importer and the upsert, in a run where every day's event page
 * fails. No day says EXHIBITION, so only the stored run can fold the days (#2575).
 */
class BerghainExhibitionRunUpsertTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val eventRepository: EventRepository = mockk(relaxed = true)
    private val clock: Clock = Clock.fixed(Instant.parse("2026-07-01T12:00:00Z"), ZoneOffset.UTC)
    private val importer = BerghainWebsiteImporter(htmlFetcher, clock)
    private val upsert = EventUpsertService(eventRepository, mockk<AssociationSyncService>(relaxed = true), clock, PerformerTyping(mockk(), eventRepository))

    private val sourceUrl = "https://www.berghain.berlin/de/program/"
    private val eventSourceId = 1L
    private val runId = "berghain:exhibition-a-shroud-woven-of-solar-threads"

    private val storedRun =
        EventEntity(
            id = 7L,
            venueId = 10L,
            title = "A Shroud Woven of Solar Threads",
            slug = "2026-10-02-berghain-a-shroud-woven-of-solar-threads",
            eventDate = LocalDate.of(2026, 10, 2),
            startTime = LocalTime.of(17, 0),
            endDate = LocalDate.of(2026, 10, 4),
            description = "An installation in the Halle.",
            sourceId = runId,
            eventSourceId = eventSourceId,
            eventType = "EXHIBITION"
        )

    @BeforeEach
    fun setUp() {
        val overview =
            listOf("83113" to "02.10.2026", "83114" to "03.10.2026", "83115" to "04.10.2026").joinToString("") { (id, date) ->
                """<a href="/de/event/$id/" class="upcoming-event"><p>Freitag <span class="font-bold">$date</span> beginn 17:00</p>""" +
                    """<h2>A SHROUD WOVEN OF SOLAR THREADS</h2><h3>Halle</h3>""" +
                    """<h4><span class="font-bold"><span>A Shroud Woven of Solar Threads</span></span></h4></a>"""
            }
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(document = Jsoup.parse("<html><body>$overview</body></html>", sourceUrl), etag = null, lastModified = null)
        // Every day's page fails: an empty document yields nothing.
        coEvery { htmlFetcher.fetchDocument(any()) } returns Jsoup.parse("<html><body></body></html>", sourceUrl)
        coEvery { eventRepository.findBySlugIn(any()) } returns emptyFlow()
    }

    private fun storeRun(stored: List<EventEntity>) {
        coEvery { eventRepository.findBySourceIdIn(any()) } answers {
            val ids = firstArg<Collection<String>>()
            stored.filter { run -> run.sourceId in ids }.asFlow()
        }
        coEvery { eventRepository.findByEventSourceIdAndEventDateGreaterThanEqual(any(), any()) } returns stored.asFlow()
    }

    private suspend fun importAndUpsert(): Pair<List<EventEntity>, Int> {
        val result = importer.importEvents(sourceUrl, null, null)
        result.shouldBeInstanceOf<ImportResult.Success>()
        val saved = slot<Iterable<EventEntity>>()
        coEvery { eventRepository.saveAll(capture(saved)) } answers { firstArg<Iterable<EventEntity>>().asFlow() }
        val outcome = upsert.upsertAndCleanup(result.events, 10L, "berghain", eventSourceId, staleCleanup = StaleCleanup.OPEN_ENDED)
        return saved.captured.toList() to outcome.inserted
    }

    @Test
    fun `every day of a stored exhibition failing updates the run and inserts nothing`() =
        runTest {
            storeRun(listOf(storedRun))

            val (saved, inserted) = importAndUpsert()

            val run = saved.single()
            run.id shouldBe 7L
            run.sourceId shouldBe runId
            run.eventType shouldBe "EXHIBITION"
            run.eventDate shouldBe LocalDate.of(2026, 10, 2)
            run.endDate shouldBe LocalDate.of(2026, 10, 4)
            run.description shouldBe "An installation in the Halle."
            inserted shouldBe 0
            coVerify(exactly = 0) { eventRepository.deleteByIdIn(any()) }
        }

    @Test
    fun `without a stored run the failed days stay single days`() =
        runTest {
            storeRun(emptyList())

            val (saved, inserted) = importAndUpsert()

            saved shouldHaveSize 3
            saved.map { it.sourceId } shouldBe listOf("berghain:83113", "berghain:83114", "berghain:83115")
            inserted shouldBe 3
        }
}
