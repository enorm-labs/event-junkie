package de.norm.events.scraper.hafenbar

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** Unit tests for [HafenbarPartyPageScraper], against saved copies of the two weekly party pages. */
class HafenbarPartyPageScraperTest {
    private val scraper = HafenbarPartyPageScraper()
    private val fridayUrl = "https://www.hafenbar-berlin.de/schlager-party-berlin/"
    private val saturdayUrl = "https://www.hafenbar-berlin.de/kaptains-club-party-berlin/"

    private fun scrape(
        fixture: String,
        url: String
    ) = scraper.scrape(
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/hafenbar/$fixture")!!
                .bufferedReader()
                .readText(),
            url
        ),
        url
    )

    @Test
    fun `scrape reads the Friday page's next night and nothing more`() {
        val events = scrape("hafenbar-freitag.html", fridayUrl)

        events shouldHaveSize 1
        val event = events.single()
        event.title shouldBe "Schlagerparty"
        event.subtitle shouldBe "deutsche Hits aus 5 Jahrzehnten"
        event.eventDate shouldBe LocalDate.of(2026, 10, 16)
        event.startTime shouldBe LocalTime.of(21, 0)
        event.doorsTime.shouldBeNull()
        event.eventType shouldBe "PARTY"
        event.genre shouldBe "Schlager"
        event.sourceUrl shouldBe fridayUrl
        event.sourceId shouldBe "hafenbar:schlager-party-berlin-2026-10-16"
        event.artists.map { it.name } shouldContainExactly listOf("DJ Funkmaster Confetti", "DJ Engel Bert")
        event.artists.map { it.role }.distinct() shouldContainExactly listOf("DJ")
        event.description!! shouldStartWith "Ahoi, liebe Disco-Nixen"
        event.description shouldContain "Freitag, den 16.10.2026 ab 21:00 Uhr!"
        event.imageUrl.shouldBeNull()
    }

    @Test
    fun `scrape reads the Saturday page and keeps the DJ name after the praise`() {
        val event = scrape("hafenbar-samstag.html", saturdayUrl).single()

        event.title shouldBe "Kaptains-Club-Party"
        event.subtitle shouldBe "Dance Classics, aktuelle Chart Hits, 90’er, 80’er und Deutsch-Pop!"
        event.eventDate shouldBe LocalDate.of(2026, 10, 17)
        event.startTime shouldBe LocalTime.of(21, 0)
        event.sourceId shouldBe "hafenbar:kaptains-club-party-berlin-2026-10-17"
        event.artists.map { it.name } shouldContainExactly listOf("DJ PUNYESH")
    }

    @Test
    fun `scrape falls back to the paragraph's two-digit year when the heading has no date`() {
        val html =
            """
            <div class="post-content"><h1>Kaptains–Club–Party am Samstag</h1></div>
            <div class="samstag_ankuendigung2"><p>Am Samstag, den <strong>24.10.26</strong> ab 22 Uhr mit <strong>DJ Lars Vegas</strong>!</p></div>
            <div class="samstag_ankuendigung3"><h2>Samstag</h2><div class="wpb_text_column">Pop</div></div>
            """.trimIndent()

        val event = scraper.scrape(Jsoup.parse(html, saturdayUrl), saturdayUrl).single()

        event.eventDate shouldBe LocalDate.of(2026, 10, 24)
        event.startTime shouldBe LocalTime.of(22, 0)
        event.artists.map { it.name } shouldContainExactly listOf("DJ Lars Vegas")
    }

    @Test
    fun `scrape skips an announcement without a date`() {
        val html =
            """
            <div class="post-content"><h1>Schlagerparty am Freitag</h1></div>
            <div class="freitag_ankuendigung2"><p>Bald geht es weiter!</p></div>
            <div class="freitag_ankuendigung3"><h2>Sommerpause</h2></div>
            """.trimIndent()

        scraper.scrape(Jsoup.parse(html, fridayUrl), fridayUrl).shouldBeEmpty()
    }

    @Test
    fun `scrape returns nothing for a page without the announcement columns`() {
        scraper.scrape(Jsoup.parse("<html><body><h1>Galerie</h1></body></html>", fridayUrl), fridayUrl).shouldBeEmpty()
    }
}
