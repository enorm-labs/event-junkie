package de.norm.events.scraper

import de.norm.events.event.EventEntity
import de.norm.events.event.EventRepository
import de.norm.events.event.EventStatus
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
import java.math.BigDecimal
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
    private val thumbnail = "https://tresorberlin.com/wp-content/uploads/2026/09/klubnacht-520x320.jpg"

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
        service = EventUpsertService(eventRepository, associationSyncService, fixedClock, PerformerTyping(mockk(), eventRepository))
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

    @Test
    fun `a listing image that stands in for the event page's yields to the stored poster`() =
        runTest {
            val written = upsertAndCapture(listingRow(detailUnavailable = true).copy(imageUrl = thumbnail, detailPageOwns = setOf(ScrapedField.IMAGE)))

            written.imageUrl shouldBe poster
        }

    @Test
    fun `a listing image the listing owns replaces the stored one`() =
        runTest {
            val written = upsertAndCapture(listingRow(detailUnavailable = true).copy(imageUrl = thumbnail))

            written.imageUrl shouldBe thumbnail
        }

    @Test
    fun `a stand-in listing image is kept when no image is stored`() =
        runTest {
            coEvery { eventRepository.findBySourceIdIn(any()) } returns listOf(stored.copy(imageUrl = null)).asFlow()

            val written = upsertAndCapture(listingRow(detailUnavailable = true).copy(imageUrl = thumbnail, detailPageOwns = setOf(ScrapedField.IMAGE)))

            written.imageUrl shouldBe thumbnail
        }

    @Test
    fun `a first import keeps the stand-in listing image`() =
        runTest {
            coEvery { eventRepository.findBySourceIdIn(any()) } returns emptyFlow()

            val written = upsertAndCapture(listingRow(detailUnavailable = true).copy(imageUrl = thumbnail, detailPageOwns = setOf(ScrapedField.IMAGE)))

            written.id.shouldBeNull()
            written.imageUrl shouldBe thumbnail
        }

    @Test
    fun `an owned title keeps the stored title and its slug over the listing's cut one`() =
        runTest {
            val cut = listingRow(detailUnavailable = true).copy(title = "Tresor Klubnacht: Tresor Rec…", detailPageOwns = setOf(ScrapedField.TITLE))

            val written = upsertAndCapture(cut)

            written.title shouldBe "Tresor Klubnacht"
            written.slug shouldBe stored.slug
        }

    @Test
    fun `an owned title still reads a cancellation from the listing's title`() =
        runTest {
            val cancelled = listingRow(detailUnavailable = true).copy(title = "ABGESAGT: Tresor Klubnacht", detailPageOwns = setOf(ScrapedField.TITLE))

            val written = upsertAndCapture(cancelled)

            written.title shouldBe "Tresor Klubnacht"
            written.status shouldBe EventStatus.CANCELLED.name
        }

    @Test
    fun `owned text, genre and type keep the stored values over the listing's`() =
        runTest {
            coEvery { eventRepository.findBySourceIdIn(any()) } returns
                listOf(stored.copy(subtitle = "Tresor Records 35 years", genre = "Techno", eventType = "CONCERT", typeIsFallback = false)).asFlow()
            val teaser =
                listingRow(detailUnavailable = true).copy(
                    subtitle = "Tresor Rec…",
                    description = "Three floors…",
                    genre = "Electronic",
                    eventType = "PARTY",
                    typeIsFallback = true,
                    detailPageOwns = setOf(ScrapedField.SUBTITLE, ScrapedField.DESCRIPTION, ScrapedField.GENRE, ScrapedField.EVENT_TYPE)
                )

            val written = upsertAndCapture(teaser)

            written.subtitle shouldBe "Tresor Records 35 years"
            written.description shouldBe "Three floors until the morning."
            written.genre shouldBe "Techno"
            written.eventType shouldBe "CONCERT"
            written.typeIsFallback shouldBe false
        }

    @Test
    fun `owned run dates keep the stored opening date and end`() =
        runTest {
            val firstListedDay = listingRow(detailUnavailable = true).copy(eventDate = nightDate.plusDays(3), detailPageOwns = setOf(ScrapedField.RUN_DATES))

            val written = upsertAndCapture(firstListedDay)

            written.eventDate shouldBe nightDate
            written.endDate shouldBe nightDate.plusDays(1)
            written.endTime shouldBe LocalTime.of(8, 0)
        }

    @Test
    fun `a field the page does not own still takes the listing's value`() =
        runTest {
            val written =
                upsertAndCapture(
                    listingRow(detailUnavailable = true).copy(
                        startTime = LocalTime.of(22, 0),
                        pricePresale = BigDecimal("15.00"),
                        detailPageOwns = setOf(ScrapedField.TITLE)
                    )
                )

            written.startTime shouldBe LocalTime.of(22, 0)
            written.pricePresale shouldBe BigDecimal("15.00")
        }

    @Test
    fun `a row whose page answered ignores the owned set`() =
        runTest {
            val answered = listingRow(detailUnavailable = false).copy(detailPageOwns = setOf(ScrapedField.TITLE))

            upsertAndCapture(answered).title shouldBe "Tresor Klubnacht: Tresor Records 35"
        }

    @Test
    fun `an owned start time and prices keep the stored ones over the listing's`() =
        runTest {
            coEvery { eventRepository.findBySourceIdIn(any()) } returns
                listOf(stored.copy(pricePresale = BigDecimal("19.80"), priceNote = "zzgl. Gebühren")).asFlow()
            val checkout =
                listingRow(detailUnavailable = true).copy(
                    startTime = LocalTime.of(22, 0),
                    pricePresale = BigDecimal("21.72"),
                    detailPageOwns = setOf(ScrapedField.START_TIME, ScrapedField.PRICES)
                )

            val written = upsertAndCapture(checkout)

            written.startTime shouldBe LocalTime.of(23, 0)
            written.pricePresale shouldBe BigDecimal("19.80")
            written.priceNote shouldBe "zzgl. Gebühren"
        }

    @Test
    fun `owned prices yield to the listing's when none are stored`() =
        runTest {
            val written =
                upsertAndCapture(listingRow(detailUnavailable = true).copy(pricePresale = BigDecimal("21.72"), detailPageOwns = setOf(ScrapedField.PRICES)))

            written.pricePresale shouldBe BigDecimal("21.72")
        }
}
