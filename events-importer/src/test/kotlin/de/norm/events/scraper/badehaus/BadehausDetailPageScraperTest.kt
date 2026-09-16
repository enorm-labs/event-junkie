package de.norm.events.scraper.badehaus

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [BadehausDetailPageScraper].
 *
 * Parses real `/events/<slug>/` detail snapshots and asserts the enriched fields
 * the listing card omits — description, start time (`Beginn`) and promoter.
 */
class BadehausDetailPageScraperTest {
    private val scraper = BadehausDetailPageScraper()

    private fun fixture(
        name: String,
        url: String
    ) = Jsoup.parse(
        javaClass.classLoader
            .getResourceAsStream("scraper/badehaus/$name")!!
            .bufferedReader()
            .readText(),
        url
    )

    @Test
    fun `parses description, start time and promoter from a detail page`() {
        val url = "https://badehaus-berlin.com/events/dominic-donner/"
        val event = scraper.scrape(fixture("badehaus-detail-promoter.html", url), url)
        event.shouldNotBeNull()

        event.title shouldBe "DOMINIC DONNER"
        event.eventDate shouldBe LocalDate.of(2026, 12, 4)
        event.doorsTime shouldBe LocalTime.of(19, 0)
        event.startTime shouldBe LocalTime.of(20, 0)
        event.promoters shouldBe listOf("Greyzone")
        event.description.shouldNotBeNull()
        event.description shouldStartWith "Dominic Donner is an emerging artist"
        event.sourceId shouldBe "badehaus:dominic-donner"
        event.sourceUrl shouldBe url
    }

    @Test
    fun `parses a detail page without a start time or promoter`() {
        val url = "https://badehaus-berlin.com/events/drunken-swallows/"
        val event = scraper.scrape(fixture("badehaus-detail-concert.html", url), url)
        event.shouldNotBeNull()

        event.title shouldBe "DRUNKEN SWALLOWS"
        event.eventDate shouldBe LocalDate.of(2026, 9, 25)
        event.doorsTime shouldBe LocalTime.of(19, 0)
        // This event lists only doors (Einlass), no Beginn, and no promoter.
        event.startTime shouldBe null
        event.promoters shouldHaveSize 0
        event.description.shouldNotBeNull()
        event.description shouldContain "ECHOS ALTER TAGE"
    }

    @Test
    fun `reads doors and start from the English Doors and Start labels`() {
        // Kemo The Blaxican: "Doors: 19:00h | Start 20:00h" — the page labels in English with an `h` suffix (#1497).
        val url = "https://badehaus-berlin.com/events/kemo-the-blaxican/"
        val event = scraper.scrape(fixture("badehaus-detail-english-labels.html", url), url)
        event.shouldNotBeNull()

        event.doorsTime shouldBe LocalTime.of(19, 0)
        event.startTime shouldBe LocalTime.of(20, 0)
        event.description.shouldNotBeNull() shouldNotContain "Doors:"
    }

    @Test
    fun `returns null when the page has no event container`() {
        val url = "https://badehaus-berlin.com/events/whatever/"
        val html = "<html><body><div class='content'><p>Not an event</p></div></body></html>"
        scraper.scrape(Jsoup.parse(html, url), url) shouldBe null
    }
}
