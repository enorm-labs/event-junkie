package de.norm.events.scraper

import de.norm.events.event.EventEntity
import de.norm.events.event.EventRepository
import de.norm.events.slug.SlugGenerator
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
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
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * The upsert's second key: matching a stored row by `event.slug` when its `sourceId` has moved.
 *
 * UFO im Velodrom failed every import for a day because the venue moved a published start time,
 * which moves the `sourceId` a Velomax event is keyed by but not the slug it is stored under
 * (#1719). The stale sweep does not free the slug for a same-day event, so the insert collided.
 */
class EventUpsertServiceSlugMatchTest {
    private val eventRepository: EventRepository = mockk(relaxed = true)
    private val associationSyncService: AssociationSyncService = mockk(relaxed = true)

    private val today = LocalDate.of(2026, 9, 22)
    private val fixedClock: Clock = Clock.fixed(today.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

    private val venueId = 10L
    private val eventSourceId = 1L
    private val venueSlug = "ufo-im-velodrom"

    /** The Spiritbox night the venue moved from 20:00 to 19:00, on the day it runs. */
    private val spiritboxSlug = SlugGenerator.slugify("$today-$venueSlug-Spiritbox")

    private lateinit var service: EventUpsertService

    private fun scraped(
        title: String,
        sourceId: String,
        startTime: LocalTime? = null,
        eventDate: LocalDate = today
    ) = ScrapedEvent(
        title = title,
        eventDate = eventDate,
        startTime = startTime,
        sourceId = sourceId,
        sourceUrl = "https://www.ufo-velodrom.de/events/event/spiritbox-ufo-2026-09-22",
        eventType = "CONCERT",
        status = "SCHEDULED"
    )

    private fun stored(
        id: Long,
        title: String,
        sourceId: String,
        slug: String,
        startTime: LocalTime? = null,
        ownedBy: Long = eventSourceId
    ) = EventEntity(
        id = id,
        venueId = venueId,
        title = title,
        slug = slug,
        eventDate = today,
        startTime = startTime,
        sourceId = sourceId,
        eventSourceId = ownedBy,
        eventType = "CONCERT",
        status = "SCHEDULED"
    )

    @BeforeEach
    fun setUp() {
        service = EventUpsertService(eventRepository, associationSyncService, fixedClock)
        coEvery { eventRepository.findBySourceIdIn(any()) } returns emptyFlow()
        coEvery { eventRepository.findBySlugIn(any()) } returns emptyFlow()
        coEvery { eventRepository.findByEventSourceIdAndEventDateBetween(any(), any(), any()) } returns emptyFlow()
        coEvery { eventRepository.saveAll(any<Iterable<EventEntity>>()) } answers {
            firstArg<Iterable<EventEntity>>().mapIndexed { index, entity -> entity.copy(id = entity.id ?: (100L + index)) }.asFlow()
        }
    }

    @Test
    fun `an event whose identity moved keeps its row and takes the new sourceId`() =
        runTest {
            val existing = stored(id = 7L, title = "Spiritbox", sourceId = "ufo:spiritbox-2000", slug = spiritboxSlug, startTime = LocalTime.of(20, 0))
            coEvery { eventRepository.findBySlugIn(listOf(spiritboxSlug)) } returns listOf(existing).asFlow()

            val saved = slot<Iterable<EventEntity>>()
            coEvery { eventRepository.saveAll(capture(saved)) } answers { firstArg<Iterable<EventEntity>>().asFlow() }

            val outcome =
                service.upsertAndCleanup(
                    listOf(scraped(title = "Spiritbox", sourceId = "ufo:spiritbox-1900", startTime = LocalTime.of(19, 0))),
                    venueId,
                    venueSlug,
                    eventSourceId
                )

            val written = saved.captured.toList()
            written.map { it.id } shouldContainExactly listOf(7L)
            written.single().sourceId shouldBe "ufo:spiritbox-1900"
            written.single().startTime shouldBe LocalTime.of(19, 0)
            written.single().slug shouldBe spiritboxSlug
            outcome.inserted shouldBe 0
            outcome.updated shouldBe 1
            outcome.droppedSlugConflict shouldBe 0
        }

    @Test
    fun `a renamed row is written even when every other field stood still`() =
        runTest {
            // Without this the row is "unchanged", the new sourceId never reaches the database, and the
            // next run matches it by slug again.
            val existing = stored(id = 7L, title = "Spiritbox", sourceId = "ufo:spiritbox-2000", slug = spiritboxSlug)
            coEvery { eventRepository.findBySlugIn(any()) } returns listOf(existing).asFlow()

            val outcome = service.upsertAndCleanup(listOf(scraped(title = "Spiritbox", sourceId = "ufo:spiritbox-1900")), venueId, venueSlug, eventSourceId)

            outcome.updated shouldBe 1
            outcome.skipped shouldBe 0
            coVerify(exactly = 1) { eventRepository.saveAll(any<Iterable<EventEntity>>()) }
        }

    @Test
    fun `a slug held by another source is refused rather than failing the batch`() =
        runTest {
            val foreign = stored(id = 9L, title = "Spiritbox", sourceId = "other:spiritbox", slug = spiritboxSlug, ownedBy = 42L)
            coEvery { eventRepository.findBySlugIn(any()) } returns listOf(foreign).asFlow()

            val outcome = service.upsertAndCleanup(listOf(scraped(title = "Spiritbox", sourceId = "ufo:spiritbox-1900")), venueId, venueSlug, eventSourceId)

            outcome.droppedSlugConflict shouldBe 1
            outcome.total shouldBe 0
            coVerify(exactly = 0) { eventRepository.saveAll(any<Iterable<EventEntity>>()) }
        }

    @Test
    fun `a slug held by a row this scrape already matched is refused`() =
        runTest {
            // The venue retitled the 20:00 entry and listed a second one at 19:00 under the old title.
            // The stored row answers the first by `sourceId` and the second by slug; taking it twice
            // would save two entities onto one id, last write winning.
            val existing = stored(id = 7L, title = "Spiritbox", sourceId = "ufo:spiritbox-2000", slug = spiritboxSlug, startTime = LocalTime.of(20, 0))
            coEvery { eventRepository.findBySourceIdIn(any()) } returns listOf(existing).asFlow()
            coEvery { eventRepository.findBySlugIn(any()) } returns listOf(existing).asFlow()

            val outcome =
                service.upsertAndCleanup(
                    listOf(
                        scraped(title = "Spiritbox — Tsunami Sea Tour", sourceId = "ufo:spiritbox-2000", startTime = LocalTime.of(20, 0)),
                        scraped(title = "Spiritbox", sourceId = "ufo:spiritbox-1900", startTime = LocalTime.of(19, 0))
                    ),
                    venueId,
                    venueSlug,
                    eventSourceId
                )

            outcome.droppedSlugConflict shouldBe 1
            outcome.updated shouldBe 1
            outcome.inserted shouldBe 0
        }

    @Test
    fun `an event whose slug nobody holds is inserted as before`() =
        runTest {
            val outcome =
                service.upsertAndCleanup(
                    listOf(scraped(title = "Trettmann", sourceId = "ufo:trettmann-2000", startTime = LocalTime.of(20, 0))),
                    venueId,
                    venueSlug,
                    eventSourceId
                )

            outcome.inserted shouldBe 1
            outcome.droppedSlugConflict shouldBe 0
        }
}
