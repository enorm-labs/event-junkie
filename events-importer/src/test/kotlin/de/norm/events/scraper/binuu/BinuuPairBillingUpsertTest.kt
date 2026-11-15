package de.norm.events.scraper.binuu

import de.norm.events.BaseControllerTest
import de.norm.events.artist.ArtistRepository
import de.norm.events.common.BERLIN
import de.norm.events.event.ArtistRole
import de.norm.events.event.EventArtistRepository
import de.norm.events.event.EventRepository
import de.norm.events.importing.EventSourceEntity
import de.norm.events.importing.EventSourceRepository
import de.norm.events.importing.EventUpsertService
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.fixtureDocument
import de.norm.events.venue.VenueEntity
import de.norm.events.venue.VenueRepository
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate

/** #5086 through the importer merge: the next import re-bills a stored `Bedo & Patron` row as its two acts. */
class BinuuPairBillingUpsertTest : BaseControllerTest() {
    @Autowired
    private lateinit var eventUpsertService: EventUpsertService

    @Autowired
    private lateinit var eventRepository: EventRepository

    @Autowired
    private lateinit var eventArtistRepository: EventArtistRepository

    @Autowired
    private lateinit var eventSourceRepository: EventSourceRepository

    @Autowired
    private lateinit var venueRepository: VenueRepository

    @Autowired
    private lateinit var artistRepository: ArtistRepository

    private val url = "https://binuu.de/de/events/nlahevqk44ms4wa"

    @Test
    fun `the next import bills the pair as two headliners`(): Unit =
        runBlocking {
            val venueId = requireNotNull(venueRepository.save(VenueEntity(name = "Bi Nuu", slug = "bi-nuu")).id)
            val sourceId =
                requireNotNull(
                    eventSourceRepository
                        .save(
                            EventSourceEntity(
                                venueId = venueId,
                                name = "Bi Nuu",
                                slug = "bi-nuu",
                                url = "https://binuu.de/de/events",
                                sourceType = "BINUU",
                                enabled = true
                            )
                        ).id
                )
            val night =
                requireNotNull(BinuuDetailPageScraper().scrape(fixtureDocument("binuu/binuu-detail-bedo-patron.html", url), url))
                    .copy(eventDate = LocalDate.now(BERLIN).plusDays(7))
            val stored = night.copy(artists = listOf(ScrapedArtist("Bedo & Patron", ArtistRole.HEADLINER)))

            eventUpsertService.upsertAndCleanup(listOf(stored), venueId, "bi-nuu", sourceId)
            billedNames(night.sourceId) shouldContainExactlyInAnyOrder listOf("Bedo & Patron")

            eventUpsertService.upsertAndCleanup(listOf(night), venueId, "bi-nuu", sourceId)
            billedNames(night.sourceId) shouldContainExactlyInAnyOrder listOf("Bedo", "Patron")
        }

    private suspend fun billedNames(sourceId: String): List<String> {
        val event = eventRepository.findBySourceIdIn(listOf(sourceId)).toList().single()
        return eventArtistRepository
            .findByEventIdIn(listOf(requireNotNull(event.id)))
            .toList()
            .map { requireNotNull(artistRepository.findById(it.artistId)).name }
    }
}
