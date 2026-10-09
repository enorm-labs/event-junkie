package de.norm.events.scraper.gaertenderwelt

import de.norm.events.event.EventEntity
import de.norm.events.event.EventRepository
import de.norm.events.importing.AssociationSyncService
import de.norm.events.importing.EventUpsertService
import de.norm.events.importing.PerformerTyping
import de.norm.events.importing.UpsertOutcome
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
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
import org.jsoup.nodes.Document
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * An exhibition listed with its whole run, through the importer and the upsert on a day inside the
 * run. The run opened a month earlier, so the row starts in the past and must still be written (#2655).
 */
class GaertenDerWeltExhibitionRunUpsertTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val eventRepository: EventRepository = mockk(relaxed = true)
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-04T10:00:00Z"), ZoneOffset.UTC)
    private val importer = GaertenDerWeltWebsiteImporter(htmlFetcher)
    private val upsert =
        EventUpsertService(
            eventRepository,
            mockk<AssociationSyncService>(relaxed = true),
            clock,
            PerformerTyping(mockk(), eventRepository),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true)
        )

    private val eventSourceId = 1L
    private val runId = "gaerten_der_welt:zwischen-himmel-und-erde-ausstellung"

    private val storedRun =
        EventEntity(
            id = 7L,
            venueId = 10L,
            title = "Zwischen Himmel und Erde: Ausstellung",
            slug = "2026-09-01-gaerten-der-welt-zwischen-himmel-und-erde-ausstellung",
            eventDate = LocalDate.of(2026, 9, 1),
            startTime = LocalTime.of(9, 0),
            endDate = LocalDate.of(2026, 11, 1),
            sourceId = runId,
            eventSourceId = eventSourceId,
            eventType = "EXHIBITION"
        )

    private fun fixture(
        name: String,
        baseUrl: String
    ): Document =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/gaertenderwelt/$name")!!
                .bufferedReader()
                .readText(),
            baseUrl
        )

    @BeforeEach
    fun setUp() {
        coEvery { htmlFetcher.fetchDocument(any()) } throws IllegalStateException("no fixture")
        coEvery { htmlFetcher.fetchDocument(ENTRY_URL) } returns fixture("gaertenderwelt-overview-runs.html", ENTRY_URL)
        coEvery { htmlFetcher.fetchDocument(PAGE2_URL) } returns
            Jsoup.parse("""<html><body><div class="tx-events2"><div class="list"></div></div></body></html>""", PAGE2_URL)
        coEvery { htmlFetcher.fetchDocument(RUN_URL) } returns fixture("gaertenderwelt-detail-exhibition.html", RUN_URL)
        coEvery { eventRepository.findBySlugIn(any()) } returns emptyFlow()
        coEvery { eventRepository.findByEventSourceIdAndEventDateBetween(any(), any(), any()) } returns emptyFlow()
    }

    private fun store(stored: List<EventEntity>) {
        coEvery { eventRepository.findBySourceIdIn(any()) } answers {
            val ids = firstArg<Collection<String>>()
            stored.filter { run -> run.sourceId in ids }.asFlow()
        }
    }

    private suspend fun importAndUpsert(): Pair<List<EventEntity>, UpsertOutcome> {
        val result = importer.importEvents(ENTRY_URL).shouldBeInstanceOf<ImportResult.Success>()
        val saved = slot<Iterable<EventEntity>>()
        coEvery { eventRepository.saveAll(capture(saved)) } answers { firstArg<Iterable<EventEntity>>().asFlow() }
        val outcome = upsert.upsertAndCleanup(result.events, 10L, "gaerten-der-welt", eventSourceId)
        return saved.captured.toList() to outcome
    }

    @Test
    fun `a run that opened before today is inserted once, from its opening to its close`() =
        runTest {
            store(emptyList())

            val (saved, outcome) = importAndUpsert()

            val run = saved.single { it.sourceId == runId }
            run.eventDate shouldBe LocalDate.of(2026, 9, 1)
            run.endDate shouldBe LocalDate.of(2026, 11, 1)
            run.eventType shouldBe "EXHIBITION"
            outcome.droppedPast shouldBe 0
            // The run and the games night; the workshop and the two tours are out of scope.
            outcome.inserted shouldBe 2
        }

    @Test
    fun `the next day's import updates the stored run and inserts no day`() =
        runTest {
            store(listOf(storedRun))

            val (saved, outcome) = importAndUpsert()

            val run = saved.single { it.sourceId == runId }
            run.id shouldBe 7L
            run.slug shouldBe storedRun.slug
            saved.count { it.title == storedRun.title } shouldBe 1
            outcome.inserted shouldBe 1
            coVerify(exactly = 0) { eventRepository.deleteByIdIn(any()) }
        }

    private companion object {
        private const val ENTRY_URL = "https://www.gaertenderwelt.de/events/veranstaltungen/"
        private const val PAGE2_URL = "https://www.gaertenderwelt.de/events/veranstaltungen/page2/"
        private const val RUN_URL = "https://www.gaertenderwelt.de/events/veranstaltungen/detail/2026-10-04_0900/zwischen-himmel-und-erde-ausstellung/"
    }
}
