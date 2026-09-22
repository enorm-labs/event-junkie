package de.norm.events.scraper

import de.norm.events.BaseControllerTest
import de.norm.events.event.EventEntity
import de.norm.events.event.EventRepository
import de.norm.events.venue.VenueEntity
import de.norm.events.venue.VenueRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import java.time.LocalTime

/**
 * An event whose identity moves while its slug stands still, against a real PostgreSQL
 * (Testcontainers).
 *
 * **The `event_slug_key` constraint is the point, so a repository double cannot stand in for it.**
 * UFO im Velodrom went dark for a day because the venue moved a published start time: Velomax puts
 * the session time in the `sourceId` and not in the slug, the upsert found no match and built an
 * INSERT, and that INSERT landed on the slug the stored row still held (#1719). One `executeMany`
 * carries the whole run, so thirteen events were lost with one row.
 *
 * The event is dated **today** deliberately: [EventUpsertService] frees a slug by deleting the stale
 * row first, but its cleanup window starts tomorrow, so a same-day move is the case that reaches the
 * database.
 */
class SlugIdentityMoveIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var eventUpsertService: EventUpsertService

    @Autowired
    private lateinit var eventRepository: EventRepository

    @Autowired
    private lateinit var eventSourceRepository: EventSourceRepository

    @Autowired
    private lateinit var venueRepository: VenueRepository

    private var venueId: Long = 0
    private var eventSourceId: Long = 0

    private val venueSlug = "ufo-im-velodrom"
    private val today: LocalDate = LocalDate.now(BERLIN)

    @BeforeEach
    fun setUpFixtures() {
        runBlocking {
            val venue = venueRepository.save(VenueEntity(name = "UFO im Velodrom", slug = venueSlug))
            venueId = requireNotNull(venue.id)
            val source =
                eventSourceRepository.save(
                    EventSourceEntity(
                        venueId = venueId,
                        name = "UFO im Velodrom",
                        slug = venueSlug,
                        url = "https://www.ufo-velodrom.de/events/",
                        sourceType = "VELOMAX",
                        enabled = true
                    )
                )
            eventSourceId = requireNotNull(source.id)
        }
    }

    private fun scraped(
        sourceId: String,
        startTime: LocalTime,
        title: String = "Spiritbox"
    ) = ScrapedEvent(
        title = title,
        eventDate = today,
        startTime = startTime,
        sourceId = sourceId,
        sourceUrl = "https://www.ufo-velodrom.de/events/event/spiritbox-ufo",
        eventType = "CONCERT"
    )

    private suspend fun upsert(vararg events: ScrapedEvent) = eventUpsertService.upsertAndCleanup(events.toList(), venueId, venueSlug, eventSourceId)

    @Test
    fun `a start time the venue moved renames the row instead of colliding with its own slug`() {
        runBlocking {
            upsert(scraped(sourceId = "ufo_im_velodrom:spiritbox-2000", startTime = LocalTime.of(20, 0))).inserted shouldBe 1
            val stored = eventRepository.findAll().toList().single()

            val outcome = upsert(scraped(sourceId = "ufo_im_velodrom:spiritbox-1900", startTime = LocalTime.of(19, 0)))

            outcome.inserted shouldBe 0
            outcome.updated shouldBe 1
            val after = eventRepository.findAll().toList().single()
            after.id shouldBe stored.id
            after.slug shouldBe stored.slug
            after.sourceId shouldBe "ufo_im_velodrom:spiritbox-1900"
            after.startTime shouldBe LocalTime.of(19, 0)
        }
    }

    @Test
    fun `an event another source already holds the slug of is refused, and the rest of the batch is saved`() {
        runBlocking {
            val otherVenue = venueRepository.save(VenueEntity(name = "Velodrom", slug = "velodrom"))
            val otherSource =
                eventSourceRepository.save(
                    EventSourceEntity(
                        venueId = requireNotNull(otherVenue.id),
                        name = "Velodrom",
                        slug = "velodrom",
                        url = "https://www.velodrom.de/events/",
                        sourceType = "VELOMAX",
                        enabled = true
                    )
                )
            // A foreign row on the slug the incoming Spiritbox event would need.
            eventRepository.save(
                EventEntity(
                    venueId = requireNotNull(otherVenue.id),
                    eventSourceId = otherSource.id,
                    title = "Spiritbox",
                    slug = "$today-$venueSlug-spiritbox",
                    eventDate = today,
                    sourceId = "velodrom:spiritbox-2000"
                )
            )

            val outcome =
                upsert(
                    scraped(sourceId = "ufo_im_velodrom:spiritbox-1900", startTime = LocalTime.of(19, 0)),
                    scraped(sourceId = "ufo_im_velodrom:trettmann-2000", startTime = LocalTime.of(20, 0), title = "Trettmann")
                )

            outcome.droppedSlugConflict shouldBe 1
            outcome.inserted shouldBe 1
            eventRepository.findAll().toList().map { it.sourceId } shouldBe
                listOf("velodrom:spiritbox-2000", "ufo_im_velodrom:trettmann-2000")
        }
    }
}
