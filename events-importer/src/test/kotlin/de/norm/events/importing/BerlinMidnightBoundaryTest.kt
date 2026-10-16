package de.norm.events.importing

import de.norm.events.event.EventEntity
import de.norm.events.event.EventRepository
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.monsterronsons.MonsterRonsonsOverviewPageScraper
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * A scraper and [EventUpsertService] read "today" from the same Berlin clock (#3401).
 *
 * 23:30 UTC on 6 August is 01:30 on 7 August in Berlin. The Monster Ronson's listing opens with an
 * 8 pm night on 6 August, which Berlin's "today" drops and UTC's keeps.
 */
class BerlinMidnightBoundaryTest {
    private val instant = Instant.parse("2026-08-06T23:30:00Z")
    private val berlinClock = Clock.fixed(instant, BERLIN)
    private val baseUrl = "https://www.karaokemonster.de/events"
    private val opener = LocalDate.of(2026, 8, 6)

    private val eventRepository: EventRepository = mockk(relaxed = true)
    private val saved = mutableListOf<EventEntity>()

    private val service =
        EventUpsertService(
            eventRepository = eventRepository,
            associationSyncService = mockk(relaxed = true),
            clock = berlinClock,
            performerTyping = PerformerTyping(mockk(), eventRepository),
            contentStamp = mockk(relaxed = true),
            enrichmentRepository = mockk(relaxed = true),
            featureSync = mockk(relaxed = true),
            changeLog = mockk(relaxed = true)
        )

    private fun scrape(clock: Clock): List<ScrapedEvent> {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/monsterronsons/monsterronsons-overview.html")!!
                .bufferedReader()
                .readText()
        return MonsterRonsonsOverviewPageScraper(clock).scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private suspend fun upsert(scraped: List<ScrapedEvent>): List<LocalDate> {
        coEvery { eventRepository.findBySourceIdIn(any()) } returns emptyFlow()
        coEvery { eventRepository.findBySlugIn(any()) } returns emptyFlow()
        coEvery { eventRepository.findByEventSourceIdAndEventDateBetween(any(), any(), any()) } returns emptyFlow()
        coEvery { eventRepository.findByEventSourceIdAndEventDateGreaterThanEqual(any(), any()) } returns emptyFlow()
        coEvery { eventRepository.saveAll(any<Iterable<EventEntity>>()) } answers {
            val entities = firstArg<Iterable<EventEntity>>().mapIndexed { index, entity -> entity.copy(id = 100L + index) }
            saved += entities
            entities.asFlow()
        }
        service.upsertAndCleanup(scraped, venueId = 1L, venueSlug = "monster-ronsons", eventSourceId = 1L)
        return saved.map { it.eventDate }
    }

    @Test
    fun `a Berlin scraper and the upsert agree on the date after Berlin midnight`() =
        runTest {
            val scraped = scrape(berlinClock)

            scraped.map { it.eventDate } shouldNotContain opener
            upsert(scraped).sorted() shouldBe scraped.map { it.eventDate }.sorted()
        }

    @Test
    fun `a UTC scraper keeps a night the upsert drops`() =
        runTest {
            val scraped = scrape(Clock.fixed(instant, ZoneOffset.UTC))

            scraped.map { it.eventDate } shouldContain opener
            upsert(scraped).sorted() shouldNotBe scraped.map { it.eventDate }.sorted()
        }
}
