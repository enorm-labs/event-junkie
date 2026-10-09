package de.norm.events.importing

import de.norm.events.event.EventEntity
import de.norm.events.event.EventRepository
import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.collapseExhibitionRuns
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The upsert of an exhibition run whose listing drops each day once it is over. Berghain lists one page per open day, so the
 * first listed day moved the stored opening and the slug later on every import (#2940).
 */
class EventUpsertServiceStoredOpeningTest {
    private val eventRepository: EventRepository = mockk(relaxed = true)

    private val today = LocalDate.of(2026, 10, 9)
    private val opening = today.minusDays(7)
    private val closing = LocalDate.of(2026, 10, 18)
    private val fixedClock: Clock = Clock.fixed(today.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

    private val venueId = 10L
    private val eventSourceId = 1L
    private val runId = "berghain:exhibition-a-shroud-woven-of-solar-threads"
    private val title = "A Shroud Woven of Solar Threads"

    private lateinit var service: EventUpsertService

    private fun storedRun(
        eventDate: LocalDate = opening,
        endDate: LocalDate = closing,
        eventType: String = EventType.EXHIBITION.name
    ) = EventEntity(
        id = 7L,
        venueId = venueId,
        title = title,
        slug = "$eventDate-berghain-panorama-bar-a-shroud-woven-of-solar-threads",
        eventDate = eventDate,
        endDate = endDate,
        sourceId = runId,
        eventSourceId = eventSourceId,
        eventType = eventType
    )

    /** The run as the importer folds it from the days the overview still lists. */
    private fun listedRun(
        firstListed: LocalDate = today,
        lastListed: LocalDate = closing
    ): ScrapedEvent =
        generateSequence(firstListed) { it.plusDays(1) }
            .takeWhile { it <= lastListed }
            .map { day ->
                ScrapedEvent(
                    title = title,
                    eventType = EventType.EXHIBITION.name,
                    eventDate = day,
                    sourceUrl = "https://www.berghain.berlin/de/event/${83120 + day.dayOfMonth}/",
                    sourceId = "berghain:${83120 + day.dayOfMonth}"
                )
            }.toList()
            .collapseExhibitionRuns { runId }
            .single()

    @BeforeEach
    fun setUp() {
        service =
            EventUpsertService(
                eventRepository,
                mockk(relaxed = true),
                fixedClock,
                PerformerTyping(mockk(), eventRepository),
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true)
            )
        coEvery { eventRepository.findBySlugIn(any()) } returns emptyFlow()
        coEvery { eventRepository.findByEventSourceIdAndEventDateGreaterThanEqual(any(), any()) } returns emptyFlow()
        coEvery { eventRepository.findByEventSourceIdAndEventDateBetween(any(), any(), any()) } returns emptyFlow()
    }

    private suspend fun upsertAndCapture(
        stored: EventEntity,
        row: ScrapedEvent
    ): EventEntity {
        coEvery { eventRepository.findBySourceIdIn(any()) } returns listOf(stored).asFlow()
        val saved = slot<Iterable<EventEntity>>()
        coEvery { eventRepository.saveAll(capture(saved)) } answers { firstArg<Iterable<EventEntity>>().asFlow() }
        service.upsertAndCleanup(listOf(row), venueId, "berghain-panorama-bar", eventSourceId, staleCleanup = StaleCleanup.OPEN_ENDED)
        return saved.captured.single()
    }

    @Test
    fun `a run whose past days left the listing keeps its stored opening and slug`() =
        runTest {
            val written = upsertAndCapture(storedRun(), listedRun())

            written.id shouldBe 7L
            written.eventDate shouldBe opening
            written.endDate shouldBe closing
            written.slug shouldBe "$opening-berghain-panorama-bar-a-shroud-woven-of-solar-threads"
        }

    @Test
    fun `a stored opening one day earlier than the first listed day is kept`() =
        runTest {
            val written = upsertAndCapture(storedRun(eventDate = today.minusDays(1)), listedRun(firstListed = today))

            written.eventDate shouldBe today.minusDays(1)
        }

    @Test
    fun `a run that has not opened yet takes the later opening the venue moved it to`() =
        runTest {
            val written = upsertAndCapture(storedRun(eventDate = today.plusDays(1)), listedRun(firstListed = today.plusDays(3)))

            written.eventDate shouldBe today.plusDays(3)
        }

    @Test
    fun `an earlier opening on the listing replaces the stored one`() =
        runTest {
            val written = upsertAndCapture(storedRun(eventDate = today), listedRun(firstListed = opening))

            written.eventDate shouldBe opening
        }

    @Test
    fun `a stored showing that closed before this run opens does not lend it its opening`() =
        runTest {
            val returning = listedRun(firstListed = today.plusDays(30), lastListed = today.plusDays(40))
            val written = upsertAndCapture(storedRun(eventDate = today.minusDays(60), endDate = today.minusDays(50)), returning)

            written.eventDate shouldBe today.plusDays(30)
        }

    @Test
    fun `an event that is not an exhibition takes the listed date`() =
        runTest {
            val festival = listedRun().copy(eventType = EventType.FESTIVAL.name, endDate = closing)
            val written = upsertAndCapture(storedRun(eventType = EventType.FESTIVAL.name), festival)

            written.eventDate shouldBe today
        }
}
