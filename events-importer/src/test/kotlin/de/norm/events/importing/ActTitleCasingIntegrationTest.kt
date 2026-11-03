package de.norm.events.importing

import de.norm.events.BaseControllerTest
import de.norm.events.artist.ArtistRepository
import de.norm.events.artist.artistSlugFor
import de.norm.events.common.BERLIN
import de.norm.events.event.ArtistRole
import de.norm.events.event.EventRepository
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
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
 * #4425 through the importer merge against a real PostgreSQL: a title in capitals that is a billed act takes the
 * act's stored name, and every other title stays as the venue prints it.
 */
class ActTitleCasingIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var eventUpsertService: EventUpsertService

    @Autowired
    private lateinit var eventRepository: EventRepository

    @Autowired
    private lateinit var eventSourceRepository: EventSourceRepository

    @Autowired
    private lateinit var venueRepository: VenueRepository

    @Autowired
    private lateinit var artistRepository: ArtistRepository

    private var venueId: Long = 0
    private var eventSourceId: Long = 0
    private val venueSlug = "astra-kulturhaus"
    private val date: LocalDate = LocalDate.now(BERLIN).plusDays(7)

    @BeforeEach
    fun setUpFixtures(): Unit =
        runBlocking {
            venueId = requireNotNull(venueRepository.save(VenueEntity(name = "Astra Kulturhaus", slug = venueSlug)).id)
            eventSourceId =
                requireNotNull(
                    eventSourceRepository
                        .save(
                            EventSourceEntity(
                                venueId = venueId,
                                name = "Astra",
                                slug = venueSlug,
                                url = "https://www.astra-berlin.de/",
                                sourceType = "ASTRA",
                                enabled = true
                            )
                        ).id
                )
        }

    private fun show(
        title: String,
        vararg acts: String,
        titleDerived: Boolean = false
    ) = ScrapedEvent(
        title = title,
        eventType = "CONCERT",
        eventDate = date,
        startTime = LocalTime.of(20, 0),
        sourceId = "astra:${title.lowercase().replace(Regex("\\W+"), "-")}",
        sourceUrl = "https://www.astra-berlin.de/events/1",
        artists =
            acts.mapIndexed { index, act ->
                ScrapedArtist(act, if (index == 0) ArtistRole.HEADLINER else ArtistRole.SUPPORT, titleDerived = titleDerived)
            }
    )

    private suspend fun importTitles(vararg events: ScrapedEvent): Map<String, String> {
        eventUpsertService.upsertAndCleanup(events.toList(), venueId, venueSlug, eventSourceId)
        return eventRepository.findBySourceIdIn(events.map { it.sourceId }).toList().associate { it.sourceId to it.title }
    }

    private suspend fun titleOf(event: ScrapedEvent): String = importTitles(event).getValue(event.sourceId)

    @Test
    fun `a capitals title that is the billed act takes the act's name`(): Unit =
        runBlocking {
            titleOf(show("CONWAY THE MACHINE", "Conway the Machine")) shouldBe "Conway the Machine"
        }

    @Test
    fun `the title takes the stored artist's name, not the venue's billing`(): Unit =
        runBlocking {
            artistRepository.insertIfAbsent("Etran de L'Aïr", artistSlugFor("ETRAN DE L'AÏR"))

            titleOf(show("ETRAN DE L'AÏR", "ETRAN DE L'AÏR")) shouldBe "Etran de L'Aïr"
        }

    @Test
    fun `a title that names no act whole stays as printed`(): Unit =
        runBlocking {
            titleOf(show("SPORTS + SUPPORT", "Sports")) shouldBe "SPORTS + SUPPORT"
            titleOf(show("SPORTS + Support", "Sports")) shouldBe "SPORTS + Support"
            titleOf(show("LATE NIGHT JAZZ", "Some Trio")) shouldBe "LATE NIGHT JAZZ"
        }

    @Test
    fun `a title in mixed case is never changed`(): Unit =
        runBlocking {
            titleOf(show("Conway THE Machine", "Conway the Machine")) shouldBe "Conway THE Machine"
        }

    @Test
    fun `a title whose act is stored in capitals stays`(): Unit =
        runBlocking {
            artistRepository.insertIfAbsent("MICHA", artistSlugFor("Micha"))

            titleOf(show("MICHA", "Micha")) shouldBe "MICHA"
        }

    @Test
    fun `a sharp s and an accented capital compare as the same letters`(): Unit =
        runBlocking {
            titleOf(show("SÜßMAUS", "Süßmaus")) shouldBe "Süßmaus"
            titleOf(show("ÁGNES OBEL", "Ágnes Obel")) shouldBe "Ágnes Obel"
        }

    @Test
    fun `the title may equal the second act`(): Unit =
        runBlocking {
            titleOf(show("THE NOTWIST", "Support Band", "The Notwist")) shouldBe "The Notwist"
        }

    @Test
    fun `a title-derived act counts as billed and takes the de-shouted name`(): Unit =
        runBlocking {
            titleOf(show("GREEN LUNG", "GREEN LUNG", titleDerived = true)) shouldBe "Green Lung"
        }

    @Test
    fun `a re-import writes the title-cased row once, and a pinned title wins`(): Unit =
        runBlocking {
            val event = show("CONWAY THE MACHINE", "Conway the Machine")
            titleOf(event) shouldBe "Conway the Machine"

            eventUpsertService.upsertAndCleanup(listOf(event), venueId, venueSlug, eventSourceId).updated shouldBe 0

            val stored = eventRepository.findBySourceIdIn(listOf(event.sourceId)).toList().single()
            eventRepository.save(stored.copy(title = "CONWAY THE MACHINE", pinnedFields = listOf("title")))
            titleOf(event) shouldBe "CONWAY THE MACHINE"
        }
}
