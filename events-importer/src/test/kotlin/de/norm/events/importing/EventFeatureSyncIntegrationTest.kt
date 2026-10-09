package de.norm.events.importing

import de.norm.events.BaseControllerTest
import de.norm.events.event.EventEntity
import de.norm.events.event.EventRepository
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.venue.VenueEntity
import de.norm.events.venue.VenueRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate

/** The party features against PostgreSQL: each import replaces `event_feature`, and an uncertain cue reaches the worklist (#2631). */
class EventFeatureSyncIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var associationSyncService: AssociationSyncService

    @Autowired
    private lateinit var featureSync: EventFeatureSync

    @Autowired
    private lateinit var featureRepository: EventFeatureRepository

    @Autowired
    private lateinit var qualityFlagRepository: EventQualityFlagRepository

    @Autowired
    private lateinit var eventRepository: EventRepository

    @Autowired
    private lateinit var venueRepository: VenueRepository

    private suspend fun persistEvent(
        sourceId: String,
        description: String?
    ): EventEntity {
        val venue = venueRepository.save(VenueEntity(name = "Test Venue", slug = "test-venue-$sourceId"))
        return eventRepository.save(
            EventEntity(
                venueId = requireNotNull(venue.id),
                title = "Acid Night",
                slug = "acid-night-$sourceId",
                eventDate = LocalDate.of(2026, 9, 1),
                sourceId = sourceId,
                description = description
            )
        )
    }

    private fun scraped(sourceId: String) =
        ScrapedEvent(
            title = "Acid Night",
            eventDate = LocalDate.of(2026, 9, 1),
            sourceId = sourceId,
            sourceUrl = "https://example.com/event",
            eventType = "PARTY",
            status = "SCHEDULED",
            artists = listOf(ScrapedArtist(name = "TBA"))
        )

    /** One import's two writers in the upsert's order: the gate replaces the flags, then the features add theirs. */
    private suspend fun import(event: EventEntity) {
        associationSyncService.resolveAndSyncAssociations(listOf(event), listOf(scraped(event.sourceId)))
        featureSync.sync(listOf(event))
    }

    @Test
    fun `features are stored with their phrase and a cue is flagged beside the gate's flags`() {
        runBlocking {
            val event = persistEvent("features:1", "Queer party until open end. A night with FLINTA* DJs.")
            val eventId = requireNotNull(event.id)

            import(event)

            featureRepository.findByEventIds(listOf(eventId)) shouldContainExactlyInAnyOrder
                listOf(
                    EventFeatureRow(eventId, "queer", "Queer party"),
                    EventFeatureRow(eventId, "open-end", "open end")
                )
            qualityFlagRepository.findByEventIds(listOf(eventId)) shouldContainExactlyInAnyOrder
                listOf(
                    EventQualityFlag(eventId, QualityFlagKind.NON_ARTIST_NAME, "TBA"),
                    EventQualityFlag(
                        eventId,
                        QualityFlagKind.UNCERTAIN_PARTY_FEATURE,
                        "flinta-only: …party until open end. A night with FLINTA* DJs."
                    )
                )
        }
    }

    @Test
    fun `the next import replaces the features and the cue with what the text says now`() {
        runBlocking {
            val event = persistEvent("features:2", "FLINTA* only, Dresscode: Fetisch")
            val eventId = requireNotNull(event.id)
            import(event)
            featureRepository.findByEventIds(listOf(eventId)).map { it.feature } shouldContainExactlyInAnyOrder
                listOf("flinta-only", "fetish-dress-code")

            import(eventRepository.save(event.copy(description = "Doors at 23:00.")))

            featureRepository.findByEventIds(listOf(eventId)).shouldBeEmpty()
            qualityFlagRepository.findByEventIds(listOf(eventId)).map { it.kind } shouldBe listOf(QualityFlagKind.NON_ARTIST_NAME)
        }
    }

    @Test
    fun `an event with no stored description is read from its title alone`() {
        runBlocking {
            val event = persistEvent("features:3", null)
            val eventId = requireNotNull(event.id)

            import(eventRepository.save(event.copy(title = "Day Rave im Garten")))

            featureRepository.findByEventIds(listOf(eventId)) shouldBe listOf(EventFeatureRow(eventId, "day-party", "Day Rave"))
        }
    }
}
