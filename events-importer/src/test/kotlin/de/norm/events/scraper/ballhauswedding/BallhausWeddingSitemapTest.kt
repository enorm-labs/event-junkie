package de.norm.events.scraper.ballhauswedding

import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class BallhausWeddingSitemapTest {
    private val programmeUrl = "https://www.ballhauswedding.de/veranstaltungen"
    private val pages =
        BallhausWeddingSitemap.datedPages(fixtureDocument("ballhauswedding/ballhauswedding-event-pages-sitemap.xml", programmeUrl))
    private val programme =
        BallhausWeddingOverviewPageScraper().scrape(
            fixtureDocument("ballhauswedding/ballhauswedding-veranstaltungen-2026-11-13.html", programmeUrl),
            programmeUrl,
            LocalDate.of(2026, 11, 12)
        )
    private val matches = BallhausWeddingSitemap.match(programme, pages, programmeUrl)

    private fun entry(
        date: LocalDate,
        title: String
    ) = programme.first { it.eventDate == date && it.title.startsWith(title) }

    @Test
    fun `reads the date, the start, the title words and the copy number from a dated slug only`() {
        pages.size shouldBe 101
        val discofox = pages.first { it.url.endsWith("/discofox-nacht-2026-12-18-20-00") }
        discofox.date shouldBe LocalDate.of(2026, 12, 18)
        discofox.start shouldBe LocalTime.of(20, 0)
        discofox.words shouldBe setOf("discofox", "nacht")
        discofox.copy shouldBe 0
        pages.first { it.url.endsWith("/die-ballhaus-show-improvisationstheater-2026-12-16-19-30-1") }.copy shouldBe 1
        pages.first { it.url.endsWith("/seniorendiskothek-2027-02-22-14-00-2") }.let {
            it.copy shouldBe 2
            it.date shouldBe LocalDate.of(2027, 2, 22)
        }
        pages.map { it.url.substringAfterLast('/') } shouldNotContain "nacht-der-1000-masken"
        pages.map { it.url.substringAfterLast('/') } shouldNotContain "seniorendiskothek-6"
    }

    @Test
    fun `matches an unlinked night by date, start and a title word, newest copy first`() {
        matches[entry(LocalDate.of(2026, 12, 18), "Discofox").sourceId] shouldBe
            listOf("https://www.ballhauswedding.de/details-registrierung/discofox-nacht-2026-12-18-20-00")
        matches[entry(LocalDate.of(2026, 12, 16), "Theatersport").sourceId] shouldBe
            listOf(
                "https://www.ballhauswedding.de/details-registrierung/die-ballhaus-show-improvisationstheater-2026-12-16-19-30-1",
                "https://www.ballhauswedding.de/details-registrierung/die-ballhaus-show-improvisationstheater-2026-12-16-19-30"
            )
    }

    @Test
    fun `leaves a night the programme links, and its other copies, to the entry that links it`() {
        val linkedSeniors = entry(LocalDate.of(2026, 11, 23), "Seniorendiskothek")
        linkedSeniors.sourceUrl shouldBe "https://www.ballhauswedding.de/details-registrierung/seniorendiskothek-2026-11-23-14-00"
        matches shouldNotContainKey linkedSeniors.sourceId

        val linkedShow = entry(LocalDate.of(2026, 11, 18), "Theatersport")
        linkedShow.sourceUrl shouldBe "https://www.ballhauswedding.de/details-registrierung/die-ballhaus-show-improvisationstheater-2026-11-18-19-30-1"
        val copy = linkedShow.copy(sourceUrl = programmeUrl, sourceId = "ballhaus_wedding:other-show")
        BallhausWeddingSitemap.match(programme + copy, pages, programmeUrl) shouldNotContainKey copy.sourceId
    }

    @Test
    fun `never matches on the title alone, on the time alone or without a start`() {
        val discofox = entry(LocalDate.of(2026, 12, 18), "Discofox")

        fun matchOf(vararg entries: ScrapedEvent) = BallhausWeddingSitemap.match(entries.toList(), pages, programmeUrl)

        matchOf(discofox.copy(startTime = LocalTime.of(21, 0))).shouldBeEmpty()
        matchOf(discofox.copy(eventDate = LocalDate.of(2026, 12, 19))).shouldBeEmpty()
        matchOf(discofox.copy(title = "Tango - die Ballhaus Milonga")).shouldBeEmpty()
        matchOf(discofox.copy(title = "Ballhaus Wedding")).shouldBeEmpty()
        matchOf(discofox.copy(startTime = null)).shouldBeEmpty()
        matchOf(discofox.copy(title = "Nacht der 1000 Masken", eventDate = LocalDate.of(2027, 2, 12))).shouldBeEmpty()
    }

    @Test
    fun `gives a night to one entry only, and none to an entry two nights fit`() {
        val discofox = entry(LocalDate.of(2026, 12, 18), "Discofox")
        val twin = discofox.copy(sourceId = "ballhaus_wedding:twin")
        BallhausWeddingSitemap.match(listOf(discofox, twin), pages, programmeUrl).keys shouldBe setOf(discofox.sourceId)

        val renamed = pages.first { it.url.endsWith("/discofox-nacht-2026-12-18-20-00") }.copy(url = "https://x/renamed", words = setOf("discofox"))
        BallhausWeddingSitemap.match(listOf(discofox), pages + renamed, programmeUrl) shouldNotContainKey discofox.sourceId
    }

    @Test
    fun `tells a cancelled copy from the live one`() {
        val scraper = BallhausWeddingEventPageScraper()
        scraper.isCancelled(fixtureDocument("ballhauswedding/ballhauswedding-event-improvisationstheater-2026-12-16-cancelled.html")) shouldBe true
        scraper.isCancelled(fixtureDocument("ballhauswedding/ballhauswedding-event-improvisationstheater-2026-12-16-1.html")) shouldBe false
        scraper.isCancelled(Jsoup.parse("<html></html>")) shouldBe false
    }
}
