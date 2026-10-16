package de.norm.events.scraper.galilaeakirche

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class GalilaeakircheOverviewPageScraperTest {
    private val baseUrl = "https://galilaea-kirche.de/programm/"
    private val events = scrape(fixture("galilaeakirche-overview.html"))

    private fun scrape(html: String) = GalilaeakircheOverviewPageScraper().scrape(Jsoup.parse(html, baseUrl), baseUrl)

    private fun bySlug(slug: String): ScrapedEvent = events.single { it.sourceId == "galilaeakirche:$slug" }

    @Test
    fun `reads each upcoming event once and skips the archive`() {
        events shouldHaveSize 10
        events.map { it.eventDate }.min() shouldBe LocalDate.of(2026, 10, 16)
        events.none { it.sourceId == "galilaeakirche:pulp-what-do-you-do-for-an-encore" } shouldBe true
    }

    @Test
    fun `reads a concert with its date, start, category and image`() {
        val event = bySlug("mikail-yakut-kintos-dance-album-release")

        event.title shouldBe "Mikail Yakut: Kinto’s Dance – Album Release"
        event.eventDate shouldBe LocalDate.of(2026, 10, 17)
        event.startTime shouldBe LocalTime.of(20, 0)
        event.eventType shouldBe EventType.CONCERT.name
        event.typeIsFallback shouldBe false
        event.status shouldBe EventStatus.SCHEDULED.name
        event.sourceUrl shouldBe "https://galilaea-kirche.de/event/mikail-yakut-kintos-dance-album-release/"
        event.imageUrl shouldBe "https://galilaea-kirche.de/wp-content/uploads/2026/09/mikail-yakut-nikodemuskirche-2-A2VJS3V9.webp"
        event.artists.map { it.name } shouldContainExactly listOf("Mikail Yakut")
    }

    @Test
    fun `bills the head of the title before the night's name`() {
        bySlug("david-moore-bing-ruth-graze-the-bell-solo-piano").artists.map { it.name } shouldContainExactly listOf("David Moore")
        bySlug("jimi-tenor-wormhole-mystic-record-release-show").artists.map { it.name } shouldContainExactly listOf("Jimi Tenor")
        bySlug("aniqo-pre-album-releaseshow-featuring-love-supreme-jonny").artists.map { it.name } shouldContainExactly listOf("ANIQO")
        bySlug("chorlibri-frauenchor-berlin-soloartists-2").artists.map { it.name } shouldContainExactly listOf("Chorlibri")
        bySlug("borderless-creatures-praesentiert-hybrid-concert-series-tag-3").artists.shouldBeEmpty()
    }

    @Test
    fun `maps the venue's categories and falls back on the title without one`() {
        bySlug("zeralda").eventType shouldBe EventType.SHOW.name
        bySlug("zeralda").artists.shouldBeEmpty()
        val uncategorised = bySlug("jimi-tenor-wormhole-mystic-record-release-show")
        uncategorised.eventType shouldBe EventType.CONCERT.name
        uncategorised.typeIsFallback shouldBe true
    }

    @Test
    fun `leaves the start empty when the event states no time`() {
        bySlug("david-moore-bing-ruth-graze-the-bell-solo-piano").startTime.shouldBeNull()
    }

    @Test
    fun `reads a cancellation, a run of days and the venue's other time spellings`() {
        val html =
            """
            <div id="vsel">
              ${row(1, "a", "Sa. 07.11.26", "abgesagt", "Konzert")}
              ${row(2, "b", "Sa. 14.11.26", "Begin: 19:30 Uhr", "Film")}
              <div id="event-3" class="vsel-content vsel-upcoming vsel-future"><h3 class="vsel-meta-title"><a href="/event/c/">C</a></h3>
                <div class="vsel-meta-date vsel-meta-start-date"><span>Start: 20.11.26</span></div>
                <div class="vsel-meta-date vsel-meta-end-date"><span>Ende: 22.11.26</span></div>
                <div class="vsel-meta-time"><span>12:00 bis 18:00 Uhr</span></div>
                <div class="vsel-meta-cats"><span><a href="/event_cat/sonstiges/">Sonstiges</a></span></div></div>
              ${row(4, "d", "So. 15.11.26", "Beginn: 20:00 Uhr", null)}
            </div>
            """.trimIndent()

        val parsed = scrape(html).associateBy { it.title }

        parsed.getValue("A").status shouldBe EventStatus.CANCELLED.name
        parsed.getValue("A").startTime.shouldBeNull()
        parsed.getValue("B").startTime shouldBe LocalTime.of(19, 30)
        parsed.getValue("B").eventType shouldBe EventType.SCREENING.name
        parsed.getValue("C").eventDate shouldBe LocalDate.of(2026, 11, 20)
        parsed.getValue("C").endDate shouldBe LocalDate.of(2026, 11, 22)
        parsed.getValue("C").startTime shouldBe LocalTime.of(12, 0)
        parsed.getValue("C").eventType shouldBe EventType.OTHER.name
        parsed.getValue("D").artists.map { it.name } shouldContainExactly listOf("D")
    }

    @Test
    fun `returns nothing for a page without events`() {
        scrape("<html><body><p class=\"vsel-no-events\">Es wurden keine Ereignisse gefunden.</p></body></html>").shouldBeEmpty()
    }

    private fun row(
        id: Int,
        slug: String,
        date: String,
        time: String,
        category: String?
    ): String {
        val cats = category?.let { """<div class="vsel-meta-cats"><span><a href="/event_cat/x/">$it</a></span></div>""" }.orEmpty()
        return """<div id="event-$id" class="vsel-content vsel-upcoming vsel-future">""" +
            """<h3 class="vsel-meta-title"><a href="/event/$slug/">${slug.uppercase()}</a></h3>""" +
            """<div class="vsel-meta-date vsel-meta-single-date"><span>$date</span></div><div class="vsel-meta-time"><span>$time</span></div>$cats</div>"""
    }

    private fun fixture(name: String) =
        javaClass.classLoader
            .getResourceAsStream("scraper/galilaeakirche/$name")!!
            .bufferedReader()
            .readText()
}
