package de.norm.events.scraper.studioiiii

import de.norm.events.BaseControllerTest
import de.norm.events.artist.ArtistRepository
import de.norm.events.artist.artistSlugFor
import de.norm.events.common.BERLIN
import de.norm.events.event.ArtistRole
import de.norm.events.event.EventArtistRepository
import de.norm.events.event.EventRepository
import de.norm.events.importing.EventSourceEntity
import de.norm.events.importing.EventSourceRepository
import de.norm.events.importing.EventUpsertService
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.venue.VenueEntity
import de.norm.events.venue.VenueRepository
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** #4453 through the importer merge: the next import re-bills a stored night, and a name the catalogue holds keeps its stored spelling. */
class StudioIiiiLineUpLabelUpsertTest : BaseControllerTest() {
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

    private val scraper = StudioIiiiOverviewPageScraper(Clock.fixed(Instant.parse("2026-11-04T10:00:00Z"), BERLIN))
    private val baseUrl = "https://studioiiii.de/"

    private fun scrapedNight(
        heading: String,
        title: String,
        descr: String
    ) = scraper
        .scrape(
            Jsoup.parse(
                """<div class="t001__wrapper"><div class="t001__uptitle">$heading</div>
                <div class="t001__title"><div>$title</div></div><div class="t001__descr"><div>$descr</div></div></div>""",
                baseUrl
            ),
            baseUrl
        ).single()
        .copy(eventDate = LocalDate.now(BERLIN).plusDays(7))

    @Test
    fun `the next import bills the act without its label and the stored row keeps its spelling`(): Unit =
        runBlocking {
            val venueId = requireNotNull(venueRepository.save(VenueEntity(name = "Studio IIII", slug = "studio-iiii")).id)
            val sourceId =
                requireNotNull(
                    eventSourceRepository
                        .save(
                            EventSourceEntity(
                                venueId = venueId,
                                name = "Studio IIII",
                                slug = "studio-iiii",
                                url = baseUrl,
                                sourceType = "STUDIO_IIII",
                                enabled = true
                            )
                        ).id
                )
            artistRepository.insertIfAbsent("olafur mowa", artistSlugFor("OLAFUR MOWA"))
            val night = scrapedNight("thur 12 NOVEMBER 7PM", "VAKUUM MAGAZINE", "LINE UP: OLAFUR MOWA (PERFORMANCE)<br>SOFTMATTER")
            val labelled = night.copy(artists = listOf("LINE UP: OLAFUR MOWA (PERFORMANCE)", "SOFTMATTER").map { ScrapedArtist(it, ArtistRole.DJ) })

            eventUpsertService.upsertAndCleanup(listOf(labelled), venueId, "studio-iiii", sourceId)
            billedNames(night.sourceId) shouldContainExactlyInAnyOrder listOf("Line Up: Olafur Mowa (Performance)", "Softmatter")

            eventUpsertService.upsertAndCleanup(listOf(night), venueId, "studio-iiii", sourceId)
            billedNames(night.sourceId) shouldContainExactlyInAnyOrder listOf("olafur mowa", "Softmatter")
        }

    private suspend fun billedNames(sourceId: String): List<String> {
        val event = eventRepository.findBySourceIdIn(listOf(sourceId)).toList().single()
        return eventArtistRepository
            .findByEventIdIn(listOf(requireNotNull(event.id)))
            .toList()
            .map { requireNotNull(artistRepository.findById(it.artistId)).name }
    }
}
