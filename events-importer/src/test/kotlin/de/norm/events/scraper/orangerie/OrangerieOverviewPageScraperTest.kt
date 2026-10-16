package de.norm.events.scraper.orangerie

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.splitBackToBack
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

class OrangerieOverviewPageScraperTest {
    private val scraper = OrangerieOverviewPageScraper()
    private val baseUrl = "https://www.orangerie-nk.de/?lang=de"
    private val events = scraper.scrape(Jsoup.parse(fixture("orangerie-home.html"), baseUrl), baseUrl)

    @Test
    fun `reads every night in the programme list`() {
        events shouldHaveSize 16
        events.first().eventDate shouldBe LocalDate.of(2026, 10, 8)
        events.last().eventDate shouldBe LocalDate.of(2027, 1, 29)
        events.map { it.sourceId }.toSet() shouldHaveSize 16
    }

    @Test
    fun `reads a free DJ night with its card's cover, tags and ticket link`() {
        val night = events.first { it.title == "Sound Canteen w/ Tobi Fries" }

        night.eventType shouldBe EventType.PARTY.name
        night.eventDate shouldBe LocalDate.of(2026, 10, 9)
        night.startTime shouldBe LocalTime.of(20, 0)
        night.sourceId shouldBe "orangerie_neukoelln:2026-10-09-sound-canteen-w-tobi-fries"
        night.free shouldBe true
        night.priceBoxOffice.shouldBeNull()
        night.genre shouldBe "House / Balearic / Leftfield"
        night.imageUrl shouldBe "https://www.orangerie-nk.de/images/events/sound-canteen-w-tobi-fries.jpg"
        night.ticketUrl shouldBe "https://rausgegangen.de/events/sound-canteen-w-tobi-fries-0/"
        night.description!! shouldStartWith "From 20:00–21:30, dinner is accompanied"
        night.artists shouldContainExactly listOf(ScrapedArtist(name = "Tobi Fries", role = "DJ"))
    }

    @Test
    fun `types a Parkside Sessions night as a concert, tagged or not`() {
        val tagged = events.first { it.title == "Parkside Sessions w/ Jacaré" }
        val untagged = events.first { it.title == "Parkside Sessions w/ Caos Sensible" }

        tagged.eventType shouldBe EventType.CONCERT.name
        tagged.genre shouldBe "Samba"
        tagged.priceBoxOffice shouldBe BigDecimal("10")
        tagged.free shouldBe false
        tagged.artists shouldContainExactly listOf(ScrapedArtist(name = "Jacaré", role = "HEADLINER"))
        untagged.eventType shouldBe EventType.CONCERT.name
        untagged.free shouldBe false
    }

    @Test
    fun `a b2b bill splits at the import boundary, and an act whose name has 'and' stays whole`() {
        events
            .first { it.title.endsWith("Bomchello b2b Terje") }
            .artists
            .flatMap { splitBackToBack(it) }
            .map { it.name } shouldContainExactly
            listOf("Bomchello", "Terje")
        events.first { it.title.endsWith("juan and only") }.artists.map { it.name } shouldContainExactly listOf("juan and only")
    }

    @Test
    fun `bills each act of a comma list after w-slash with the night's role`() {
        val nights =
            scrapeCards(
                "ÍNTIMO w/ Nadia Wise, Sarah Ninon, Jelle from the Block" to "",
                "Hang Jack w/ CRIES, Kallaloo, Vio PRG" to "",
                "Parkside Sessions w/ Aruto, Os Camarões" to "",
                "Sound Canteen w/ Bomchello b2b Terje, mnm," to ""
            )

        nights[0].artists shouldContainExactly
            listOf("Nadia Wise", "Sarah Ninon", "Jelle from the Block").map { ScrapedArtist(name = it, role = "DJ") }
        nights[1].artists shouldContainExactly listOf("CRIES", "Kallaloo", "Vio PRG").map { ScrapedArtist(name = it, role = "DJ") }
        nights[2].artists shouldContainExactly listOf("Aruto", "Os Camarões").map { ScrapedArtist(name = it, role = "HEADLINER") }
        nights[3].artists.flatMap { splitBackToBack(it) }.map { it.name } shouldContainExactly listOf("Bomchello", "Terje", "mnm")
    }

    @Test
    fun `splits the last two acts of a comma list at their conjunction`() {
        val nights = scrapeCards("ÍNTIMO w/ Sarah Ninon, The Brahma & Jelle from the Block" to "")

        nights.single().artists shouldContainExactly
            listOf("Sarah Ninon", "The Brahma", "Jelle from the Block").map { ScrapedArtist(name = it, role = "DJ") }
    }

    @Test
    fun `names nobody for a crew or series night`() {
        events.first { it.title.startsWith("Gardens of Disco") }.artists.shouldBeEmpty()
        events.first { it.title.startsWith("Nice Tries") }.artists.shouldBeEmpty()
    }

    @Test
    fun `types a food pop-up OTHER and keeps its tag out of the genre`() {
        val nights =
            scrapeCards(
                "Boca a Boca Empanada" to """<span class="event-tag tag-food">South-American empanadas with a twist</span>""",
                "Boca a Boca" to """<span class="event-tag tag-food"><span lang="de">Food Pop-up</span><span lang="en">Food pop-up</span></span>""",
                "Empanadas w/ Tobi Fries" to """<span class="event-tag tag-food">Empanadas</span><span class="event-tag tag-music">House</span>"""
            )

        nights.map { it.eventType } shouldContainExactly listOf(EventType.OTHER.name, EventType.OTHER.name, EventType.PARTY.name)
        nights.map { it.genre } shouldContainExactly listOf(null, null, "House")
    }

    @Test
    fun `reads one language of a bilingual tag`() {
        val night =
            scrapeCards("Slow Night" to """<span class="event-tag tag-music"><span lang="de">Langsame Lieder</span><span lang="en">Slow Jams</span></span>""")

        night.single().genre shouldBe "Slow Jams"
    }

    @Test
    fun `returns nothing for a page without the programme list`() {
        scraper.scrape(Jsoup.parse("<html><body></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }

    /** A programme of 2026-10-11 nights, one card each, built from the live page's markup. */
    private fun scrapeCards(vararg nights: Pair<String, String>): List<ScrapedEvent> {
        val items =
            nights.mapIndexed { i, (name, _) ->
                """{"@type":"Event","name":"$name","startDate":"2026-10-1${i}T17:00:00+02:00"}"""
            }
        val cards =
            nights.joinToString("") { (name, tags) ->
                """<div class="card"><h3 class="card-title">$name</h3><span class="og-genres">$tags</span></div>"""
            }
        val html =
            """<html><head><script type="application/ld+json">{"@type":"ItemList","itemListElement":[${items.joinToString(",")}]}</script></head>""" +
                """<body>$cards</body></html>"""
        return scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/orangerie/$name")!!
            .bufferedReader()
            .readText()
}
