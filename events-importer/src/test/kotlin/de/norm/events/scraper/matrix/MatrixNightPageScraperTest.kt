package de.norm.events.scraper.matrix

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [MatrixNightPageScraper], on the seven night pages captured on 2026-10-08. Every date comes from the page, so no clock is needed.
 */
class MatrixNightPageScraperTest {
    private val scraper = MatrixNightPageScraper()

    private fun nightUrl(format: String) = "https://www.matrix-berlin.de/de/night/$format"

    private fun html(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/matrix/$name")!!
            .bufferedReader()
            .readText()

    private fun night(
        format: String,
        page: String = html("matrix-night-$format-de.html")
    ) = scraper.scrape(Jsoup.parse(page, nightUrl(format)), nightUrl(format))

    @Test
    fun `extracts all fields of a night`() {
        val event = night("social").shouldNotBeNull()

        event.title shouldBe "Social"
        event.eventType shouldBe EventType.PARTY.name
        event.eventDate shouldBe LocalDate.of(2026, 10, 8)
        event.startTime shouldBe LocalTime.of(22, 0)
        // The page names no end, so a night past midnight has none.
        event.endTime.shouldBeNull()
        event.endDate.shouldBeNull()
        event.genre shouldBe "Afrobeats, Dance classics, Hip-hop, House, R'n'B, Top 40"
        event.imageUrl shouldBe "https://www.matrix-berlin.de/nights/social-bg.webp"
        event.sourceUrl shouldBe nightUrl("social")
        event.sourceId shouldBe "matrix:2026-10-08-social"
        event.description shouldBe "People. Music. Connection.\n1 Floor geöffnet\nNur 5 € Eintritt für Ladies & Studenten bis 0 Uhr!"
        event.artists shouldContainExactly listOf(ScrapedArtist(name = "DJ SizMo", role = "DJ"))
        // The offer names an amount, so it is the note, but Matrix publishes no door price.
        event.priceNote shouldBe "Nur 5 € Eintritt für Ladies & Studenten bis 0 Uhr!"
        event.priceBoxOffice.shouldBeNull()
        event.pricePresale.shouldBeNull()
        event.subtitle.shouldBeNull()
    }

    @Test
    fun `reads one dated night from each of the seven format pages`() {
        val events = listOf("social", "icon", "matrix", "legacy", "reboot", "switch", "velvet").map { night(it).shouldNotBeNull() }

        events.map { it.eventDate } shouldContainExactly (8..14).map { LocalDate.of(2026, 10, it) }
        events.map { it.title } shouldContainExactly listOf("Social", "Icon", "Matrix", "Legacy", "Reboot", "Switch", "Velvet")
    }

    @Test
    fun `splits a back-to-back entry and reads every entry as a DJ`() {
        night("matrix").shouldNotBeNull().artists shouldContainExactly
            listOf(
                ScrapedArtist(name = "DJ JC", role = "DJ"),
                ScrapedArtist(name = "DJ GUS", role = "DJ"),
                ScrapedArtist(name = "DJ Le Trace", role = "DJ"),
                ScrapedArtist(name = "MC Caramel", role = "DJ")
            )
    }

    @Test
    fun `drops the crew or radio show after a resident's name`() {
        night("legacy").shouldNotBeNull().artists.map { it.name } shouldContainExactly listOf("DJ R2V")
        night("switch").shouldNotBeNull().artists.map { it.name } shouldContainExactly listOf("DJ TC")
    }

    @Test
    fun `keeps a free-for-ladies offer out of the price note`() {
        val event = night("velvet").shouldNotBeNull()

        // "Freier Eintritt für Ladies bis 0 Uhr!" names no amount; in the note detectFree would mark the night free.
        event.priceNote.shouldBeNull()
        event.free shouldBe false
        event.toEventEntity(venueId = 1L, venueSlug = "matrix", eventSourceId = 1L).free shouldBe false
    }

    @Test
    fun `skips a format page that shows no next date`() {
        val page = html("matrix-night-social-de.html").replace("Do., 08.10.2026", "Bald wieder")

        night("social", page).shouldBeNull()
    }

    @Test
    fun `keeps a night whose line-up is empty`() {
        val document = Jsoup.parse(html("matrix-night-social-de.html"), nightUrl("social"))
        document.select(".nd-dj").remove()

        val event = scraper.scrape(document, nightUrl("social")).shouldNotBeNull()
        event.artists.shouldBeEmpty()
        event.eventDate shouldBe LocalDate.of(2026, 10, 8)
    }

    @Test
    fun `keeps a long name whole`() {
        val name = "DJ Supercalifragilistic Expialidocious Featuring The Very Long Residency Name"
        val document = withLineUp(name)

        scraper
            .scrape(document, nightUrl("social"))
            .shouldNotBeNull()
            .artists
            .map { it.name } shouldContainExactly listOf(name)
    }

    @Test
    fun `returns null for a page that is no night page`() {
        scraper.scrape(Jsoup.parse("<html><body><p>Wartung</p></body></html>", nightUrl("social")), nightUrl("social")).shouldBeNull()
    }

    private fun withLineUp(name: String): Document =
        Jsoup.parse(html("matrix-night-social-de.html"), nightUrl("social")).also { document ->
            document.selectFirst(".nd-dj b").shouldNotBeNull().text(name)
        }
}
