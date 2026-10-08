package de.norm.events.importing

import de.norm.events.BaseControllerTest
import de.norm.events.event.EventEntity
import de.norm.events.event.EventRepository
import de.norm.events.event.EventStatus
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.venue.VenueEntity
import de.norm.events.venue.VenueRepository
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.await
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * `content_changed_at` against a real PostgreSQL (#2768): an unchanged re-import keeps it, a change
 * the event page shows moves it, and a change it does not show leaves it.
 */
class ContentStampIntegrationTest : BaseControllerTest() {
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
    private val venueSlug = "festsaal-kreuzberg"
    private val date: LocalDate = LocalDate.now(BERLIN).plusDays(7)

    @BeforeEach
    fun setUpFixtures() {
        runBlocking {
            venueId = requireNotNull(venueRepository.save(VenueEntity(name = "Festsaal Kreuzberg", slug = venueSlug)).id)
            eventSourceId =
                requireNotNull(
                    eventSourceRepository
                        .save(
                            EventSourceEntity(
                                venueId = venueId,
                                name = "Festsaal",
                                slug = venueSlug,
                                url = "https://festsaal-kreuzberg.de/",
                                sourceType = "FESTSAAL",
                                enabled = true
                            )
                        ).id
                )
        }
    }

    /** A night over midnight, with a lineup and a promoter, as the venue publishes it. */
    private fun published(sourceId: String = "festsaal:nachtschicht") =
        ScrapedEvent(
            title = "Nachtschicht — ${"Ein sehr langer Untertitel, wie ihn Veranstalter gern schreiben ".repeat(3).trim()}",
            eventType = "PARTY",
            eventDate = date,
            startTime = LocalTime.of(23, 0),
            endDate = date.plusDays(1),
            endTime = LocalTime.of(6, 0),
            sourceId = sourceId,
            sourceUrl = "https://festsaal-kreuzberg.de/events/nachtschicht",
            description = "Eine Nacht bis zum Morgen.",
            pricePresale = BigDecimal("15"),
            artists = listOf(ScrapedArtist("DJ Kollektiv Ost", "DJ"), ScrapedArtist("Mara Lind", "DJ")),
            promoters = listOf("Nachtschicht Kollektiv")
        )

    private suspend fun import(vararg events: ScrapedEvent) = eventUpsertService.upsertAndCleanup(events.toList(), venueId, venueSlug, eventSourceId)

    private suspend fun stored(sourceId: String = "festsaal:nachtschicht"): EventEntity = eventRepository.findBySourceIdIn(listOf(sourceId)).toList().single()

    @Test
    fun `a new event is stamped, and an unchanged re-import keeps the hash and the stamp`(): Unit =
        runBlocking {
            import(published())
            val first = stored()
            first.contentHash.shouldNotBeNull()
            first.contentChangedAt.shouldNotBeNull()

            import(published()).skipped shouldBe 1
            val second = stored()
            second.contentHash shouldBe first.contentHash
            second.contentChangedAt shouldBe first.contentChangedAt
            second.updatedAt shouldBe first.updatedAt
        }

    @Test
    fun `a changed start time moves the stamp`(): Unit =
        runBlocking {
            import(published())
            val before = stored()

            import(published().copy(startTime = LocalTime.of(23, 30)))
            val after = stored()
            after.contentHash shouldNotBe before.contentHash
            after.contentChangedAt.shouldNotBeNull() shouldBeGreaterThan before.contentChangedAt.shouldNotBeNull()
        }

    @Test
    fun `a changed lineup moves the stamp, though the event row itself is unchanged`(): Unit =
        runBlocking {
            import(published())
            val before = stored()

            import(published().let { it.copy(artists = it.artists + ScrapedArtist("Tomas Weil", "DJ")) })
            stored().contentChangedAt.shouldNotBeNull() shouldBeGreaterThan before.contentChangedAt.shouldNotBeNull()
        }

    @Test
    fun `a change the page does not show is written but keeps the stamp`(): Unit =
        runBlocking {
            import(published())
            val before = stored()

            import(published().copy(sourceUrl = "https://festsaal-kreuzberg.de/programm/nachtschicht")).updated shouldBe 1
            val after = stored()
            after.sourceUrl shouldBe "https://festsaal-kreuzberg.de/programm/nachtschicht"
            after.contentHash shouldBe before.contentHash
            after.contentChangedAt shouldBe before.contentChangedAt
        }

    @Test
    fun `every status change moves the stamp, and the same status again keeps it`(): Unit =
        runBlocking {
            val others = EventStatus.entries - EventStatus.SCHEDULED
            val events = others.associateWith { published("festsaal:nachtschicht-${it.name.lowercase()}").copy(title = "Nachtschicht Nummer ${it.ordinal}") }
            import(*events.values.toTypedArray())
            val scheduled = events.mapValues { (_, event) -> stored(event.sourceId) }

            val changed =
                events.mapValues { (status, event) ->
                    event.copy(
                        status = status.name,
                        statusNote =
                            "Verlegt ins Lido".takeIf {
                                status ==
                                    EventStatus.RELOCATED
                            }
                    )
                }
            import(*changed.values.toTypedArray())
            val moved = changed.mapValues { (_, event) -> stored(event.sourceId) }
            others.forEach { status ->
                moved.getValue(status).status shouldBe status.name
                moved.getValue(status).contentChangedAt.shouldNotBeNull() shouldBeGreaterThan scheduled.getValue(status).contentChangedAt.shouldNotBeNull()
            }

            import(*changed.values.toTypedArray())
            others.forEach { status -> stored(changed.getValue(status).sourceId).contentChangedAt shouldBe moved.getValue(status).contentChangedAt }
        }

    @Test
    fun `a row with no hash yet takes one and keeps the date the migration backfilled`(): Unit =
        runBlocking {
            import(published())
            databaseClient.sql("UPDATE events.event SET content_hash = NULL WHERE source_id = 'festsaal:nachtschicht'").await()
            val backfilled = stored().contentChangedAt

            import(published().copy(startTime = LocalTime.of(23, 30)))
            val after = stored()
            after.contentHash.shouldNotBeNull()
            after.contentChangedAt shouldBe backfilled
        }
}
