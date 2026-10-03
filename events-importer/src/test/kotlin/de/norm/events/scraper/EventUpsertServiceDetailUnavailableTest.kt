package de.norm.events.scraper

import de.norm.events.event.EventEntity
import de.norm.events.event.EventRepository
import io.kotest.matchers.nulls.shouldBeNull
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
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * The upsert of a row whose detail page yielded nothing. One run in which every Tresor event page
 * redirected to the home page blanked the start time and image of all 24 upcoming rows (#2421).
 */
class EventUpsertServiceDetailUnavailableTest {
    private val eventRepository: EventRepository = mockk(relaxed = true)
    private val associationSyncService: AssociationSyncService = mockk(relaxed = true)

    private val today = LocalDate.of(2026, 10, 2)
    private val nightDate = today.plusDays(8)
    private val fixedClock: Clock = Clock.fixed(today.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

    private val venueId = 10L
    private val eventSourceId = 1L
    private val sourceId = "tresor:20261010-tresor-klubnacht"
    private val poster = "https://tresorberlin.com/wp-content/uploads/2026/09/klubnacht-1536x1536.jpg"

    private lateinit var service: EventUpsertService

    /** The row an earlier run built from the event page, its title since changed on the listing. */
    private val stored =
        EventEntity(
            id = 7L,
            venueId = venueId,
            title = "Tresor Klubnacht",
            slug = "2026-10-10-tresor-tresor-klubnacht",
            eventDate = nightDate,
            startTime = LocalTime.of(23, 0),
            endDate = nightDate.plusDays(1),
            endTime = LocalTime.of(8, 0),
            imageUrl = poster,
            description = "Three floors until the morning.",
            sourceId = sourceId,
            eventSourceId = eventSourceId,
            eventType = "PARTY"
        )

    /** What the listing alone carries: no start time, no image, no blurb. */
    private fun listingRow(detailUnavailable: Boolean) =
        ScrapedEvent(
            title = "Tresor Klubnacht: Tresor Records 35",
            eventType = "PARTY",
            eventDate = nightDate,
            sourceUrl = "https://tresorberlin.com/event/20261010-tresor-klubnacht/",
            sourceId = sourceId,
            detailUnavailable = detailUnavailable
        )

    @BeforeEach
    fun setUp() {
        service = EventUpsertService(eventRepository, associationSyncService, fixedClock)
        coEvery { eventRepository.findBySourceIdIn(any()) } returns listOf(stored).asFlow()
        coEvery { eventRepository.findBySlugIn(any()) } returns emptyFlow()
        coEvery { eventRepository.findByEventSourceIdAndEventDateBetween(any(), any(), any()) } returns emptyFlow()
    }

    private suspend fun upsertAndCapture(row: ScrapedEvent): EventEntity {
        val saved = slot<Iterable<EventEntity>>()
        coEvery { eventRepository.saveAll(capture(saved)) } answers { firstArg<Iterable<EventEntity>>().asFlow() }
        service.upsertAndCleanup(listOf(row), venueId, "tresor", eventSourceId)
        return saved.captured.single()
    }

    @Test
    fun `a stored start time and image survive an import whose detail page yields nothing`() =
        runTest {
            val written = upsertAndCapture(listingRow(detailUnavailable = true))

            written.id shouldBe 7L
            written.startTime shouldBe LocalTime.of(23, 0)
            written.imageUrl shouldBe poster
            written.description shouldBe "Three floors until the morning."
            written.endDate shouldBe nightDate.plusDays(1)
            written.endTime shouldBe LocalTime.of(8, 0)
            // The listing's own fields still win.
            written.title shouldBe "Tresor Klubnacht: Tresor Records 35"
        }

    @Test
    fun `a row whose detail page answered replaces the stored fields with what it found`() =
        runTest {
            val written = upsertAndCapture(listingRow(detailUnavailable = false))

            written.startTime.shouldBeNull()
            written.imageUrl.shouldBeNull()
        }

    @Test
    fun `a field the listing does carry replaces the stored one`() =
        runTest {
            val written = upsertAndCapture(listingRow(detailUnavailable = true).copy(startTime = LocalTime.of(22, 0)))

            written.startTime shouldBe LocalTime.of(22, 0)
            written.imageUrl shouldBe poster
        }
}
