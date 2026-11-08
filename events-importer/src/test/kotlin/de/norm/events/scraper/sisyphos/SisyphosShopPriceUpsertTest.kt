package de.norm.events.scraper.sisyphos

import de.norm.events.common.BERLIN
import de.norm.events.event.EventEntity
import de.norm.events.event.EventRepository
import de.norm.events.importing.ActTitleCasing
import de.norm.events.importing.AssociationSyncService
import de.norm.events.importing.EventUpsertService
import de.norm.events.importing.PerformerTyping
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.HttpFetchException
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.fixtureText
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The shop's presale price of a stored Sisyphos night, through the importer and the upsert, when the shop refuses the read (#4696).
 */
class SisyphosShopPriceUpsertTest {
    private val apiClient: ApiClient = mockk()
    private val htmlFetcher: HtmlFetcher = mockk()
    private val eventRepository: EventRepository = mockk(relaxed = true)
    private val clock: Clock = Clock.fixed(LocalDateTime.of(2026, 9, 30, 12, 0).atZone(BERLIN).toInstant(), BERLIN)
    private val upsert =
        EventUpsertService(
            eventRepository,
            mockk<AssociationSyncService>(relaxed = true),
            clock,
            PerformerTyping(mockk(), eventRepository),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            ActTitleCasing(mockk { coEvery { storedNames(any()) } returns emptyMap() })
        )

    private val feedUrl = "https://www.sisyphos-berlin.net/collections/tickets/products.json"
    private val calendarUrl = "https://dashboard.sisyphos-berlin.net/kalender-media/events.json"
    private val eventSourceId = 1L
    private val productUrl = "https://www.sisyphos-berlin.net/products/generations-10-okt-2026"
    private val generations = "sisyphos:2026-10-10"

    /** The generationS night as a good shop read stored it. */
    private val stored =
        EventEntity(
            id = 7L,
            venueId = 10L,
            title = "generationS",
            slug = "2026-10-10-generations",
            eventDate = LocalDate.of(2026, 10, 10),
            startTime = LocalTime.of(14, 0),
            ticketUrl = productUrl,
            pricePresale = BigDecimal("20.00"),
            sourceId = generations,
            eventSourceId = eventSourceId,
            eventType = "PARTY"
        )

    private fun importer() = SisyphosWebsiteImporter(apiClient, htmlFetcher, clock)

    @BeforeEach
    fun setUp() {
        coEvery { apiClient.fetchJson(calendarUrl) } returns fixtureText("sisyphos/sisyphos-calendar.json")
        coEvery { eventRepository.findBySourceIdIn(any()) } returns listOf(stored).asFlow()
        coEvery { eventRepository.findBySlugIn(any()) } returns emptyFlow()
        coEvery { eventRepository.findByEventSourceIdAndEventDateBetween(any(), any(), any()) } returns emptyFlow()
    }

    private fun refuseShop() {
        coEvery { apiClient.fetchJson(feedUrl) } throws HttpFetchException(429, feedUrl)
    }

    /** The generationS row the upsert writes from [importer]'s next run. */
    private suspend fun written(importer: SisyphosWebsiteImporter): EventEntity {
        val result = importer.importEvents(feedUrl).shouldBeInstanceOf<ImportResult.Success>()
        val saved = slot<Iterable<EventEntity>>()
        coEvery { eventRepository.saveAll(capture(saved)) } answers { firstArg<Iterable<EventEntity>>().asFlow() }
        upsert.upsertAndCleanup(result.events, 10L, "sisyphos", eventSourceId)
        return saved.captured.single { it.sourceId == generations }
    }

    @Test
    fun `a refused shop read leaves the stored price unchanged`() =
        runTest {
            refuseShop()
            val written = written(importer())
            written.id shouldBe 7L
            written.pricePresale shouldBe BigDecimal("20.00")
            written.ticketUrl shouldBe productUrl
        }

    @Test
    fun `a refused shop read after a restart keeps the price the earlier process stored`() =
        runTest {
            coEvery { apiClient.fetchJson(feedUrl) } returns fixtureText("sisyphos/sisyphos-tickets.json")
            importer().importEvents(feedUrl).shouldBeInstanceOf<ImportResult.Success>()
            refuseShop()
            written(importer()).pricePresale shouldBe BigDecimal("20.00")
        }

    @Test
    fun `a refused read after a good one in the same process joins the price that read saw`() =
        runTest {
            coEvery { apiClient.fetchJson(feedUrl) } returns fixtureText("sisyphos/sisyphos-tickets.json")
            val importer = importer()
            importer.importEvents(feedUrl).shouldBeInstanceOf<ImportResult.Success>()
            refuseShop()
            written(importer).pricePresale shouldBe BigDecimal("25.00")
        }

    @Test
    fun `a good shop read that no longer lists the night clears the stored price`() =
        runTest {
            coEvery { apiClient.fetchJson(feedUrl) } returns """{"products": []}"""
            written(importer()).pricePresale.shouldBeNull()
        }
}
