package de.norm.events.scraper.zurklappe

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [ZurKlappeOverviewPageScraper], against a saved copy of the real `/events` page.
 *
 * The live programme leaves `djs`, `raLink` and `coverImage` empty, so the tests for those fields
 * push a hand-written payload through the same flight-chunk path.
 */
class ZurKlappeOverviewPageScraperTest {
    private val scraper = ZurKlappeOverviewPageScraper()
    private val baseUrl = "https://zurklappe.org/events"

    private fun fixture(name: String = "zurklappe-overview.html") =
        javaClass.classLoader
            .getResourceAsStream("scraper/zurklappe/$name")!!
            .bufferedReader()
            .readText()

    private fun scrapeFixture() = scraper.scrape(Jsoup.parse(fixture(), baseUrl), baseUrl)

    private fun scrapeHtml(html: String) = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)

    /** Runs [block] and returns its result beside every line the scraper logged. */
    private fun <T> withScraperLog(block: () -> T): Pair<T, List<ILoggingEvent>> {
        val logger = LoggerFactory.getLogger(ZurKlappeOverviewPageScraper::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        return try {
            block() to appender.list.toList()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    private fun scrapePayload(events: String) =
        JsonMapper.builder().build().writeValueAsString("""{"events":$events}""").let { chunk ->
            val html = """<html><body><script>self.__next_f.push([1,$chunk])</script></body></html>"""
            scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
        }

    @Test
    fun `scrape reads the upcoming nights and not the past list`() {
        val events = scrapeFixture()

        events shouldHaveSize 2
        events.map { it.title } shouldBe
            listOf("Klappnacht", "Sachsentrance - The Jakob Sister & RaverPik aka Hitstorm ALL NIGHT LONG")
    }

    @Test
    fun `scrape maps a fully populated night`() {
        val event = scrapeFixture()[1]

        event.eventDate shouldBe LocalDate.of(2026, 10, 8)
        event.startTime shouldBe LocalTime.of(23, 0)
        // "23:00 – 06:00": the end rolls into the next morning.
        event.endDate shouldBe LocalDate.of(2026, 10, 9)
        event.endTime shouldBe LocalTime.of(6, 0)
        event.eventType shouldBe "PARTY"
        event.description!! shouldStartWith "The Jakob Sister & RaverPik — ALL NIGHT LONG!!!"
        event.sourceUrl shouldBe
            "https://zurklappe.org/events/sachsentrance-the-jakob-sister-raverpik-aka-hitstorm-all-night-long"
        event.sourceId shouldBe "zur_klappe:sachsentrance-the-jakob-sister-raverpik-aka-hitstorm-all-night-long"
    }

    @Test
    fun `scrape treats the payload's undefined markers and empty strings as absent`() {
        val event = scrapeFixture().first()

        event.title shouldBe "Klappnacht"
        event.eventDate shouldBe LocalDate.of(2026, 10, 3)
        event.endDate.shouldBeNull()
        event.endTime.shouldBeNull()
        event.description.shouldBeNull()
        event.imageUrl.shouldBeNull()
        event.ticketUrl.shouldBeNull()
        event.artists.shouldBeEmpty()
    }

    @Test
    fun `scrape reads the DJs, the RA link and the cover image when a night has them`() {
        val event =
            scrapePayload(
                """[{"slug":"x","title":"X","date":"10.10.2026","time":"23:00","endTime":"${'$'}undefined",
                "djs":[{"name":"The Jakob Sister"},{"name":"RaverPik"}],"raLink":"https://ra.co/events/1",
                "coverImage":"https://example.org/flyer.jpg"}]"""
            ).single()

        event.artists.map { it.name } shouldBe listOf("The Jakob Sister", "RaverPik")
        event.ticketUrl shouldBe "https://ra.co/events/1"
        event.imageUrl shouldBe "https://example.org/flyer.jpg"
    }

    @Test
    fun `scrape skips a private night and one without a usable date`() {
        scrapePayload(
            """[{"slug":"a","title":"A","date":"10.10.2026","isPrivate":true},
            {"slug":"b","title":"B","date":"TBA"}]"""
        ).shouldBeEmpty()
    }

    @Test
    fun `scrape survives a page with no flight payload at all`() {
        scrapeHtml("<html><body><p>Soon</p></body></html>").shouldBeEmpty()
    }

    @Test
    fun `scrape reads the empty-state page as an empty programme, not a broken payload`() {
        val (events, lines) = withScraperLog { scrapeHtml(fixture("zurklappe-overview-empty.html")) }

        events.shouldBeEmpty()
        lines.map { it.level } shouldBe listOf(Level.INFO)
        lines.single().formattedMessage shouldBe "Zur Klappe lists no upcoming events"
    }

    @Test
    fun `scrape warns when the events array is missing and the page states no empty programme`() {
        // The real empty page, minus its empty-state line: the payload alone cannot tell the two apart.
        val html = fixture("zurklappe-overview-empty.html").replace("No upcoming events.", "Coming soon.")

        val (events, lines) = withScraperLog { scrapeHtml(html) }

        events.shouldBeEmpty()
        lines.map { it.level } shouldBe listOf(Level.WARN)
        lines.single().formattedMessage shouldBe "No events array in Zur Klappe's flight payload"
    }

    @Test
    fun `scrape matches the empty-state text only as a whole paragraph`() {
        val (_, lines) =
            withScraperLog {
                scrapeHtml("<html><body><p>No upcoming events. Check back soon for our winter season.</p></body></html>")
            }

        lines.map { it.level } shouldBe listOf(Level.WARN)
    }

    @Test
    fun `scrape reads the events array even when the page also states the empty-state text`() {
        val chunk = JsonMapper.builder().build().writeValueAsString("""{"events":[{"slug":"x","title":"X","date":"10.10.2026"}]}""")
        val html = """<html><body><p>No upcoming events.</p><script>self.__next_f.push([1,$chunk])</script></body></html>"""

        val (events, lines) = withScraperLog { scrapeHtml(html) }

        events.single().title shouldBe "X"
        lines.shouldBeEmpty()
    }
}
