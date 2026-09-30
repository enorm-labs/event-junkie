package de.norm.events.scraper.tresor

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [TresorDetailPageScraper].
 *
 * The event page exists to supply the two fields the listing lacks — a start time derived from the
 * night's opening set, and a blurb that must be cut off before the venue's standing policy text.
 */
class TresorDetailPageScraperTest {
    private val scraper = TresorDetailPageScraper()

    private fun scrape(
        fixture: String,
        slug: String
    ): ScrapedEvent? {
        val url = "https://tresorberlin.com/event/$slug/"
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/tresor/$fixture")!!
                .bufferedReader()
                .readText()
        return scraper.scrape(Jsoup.parse(html, url), url)
    }

    @Test
    fun `derives the start time from the night's opening set`() {
        val klubnacht = scrape("tresor-detail-klubnacht.html", "20260801-tresor-klubnacht")
        klubnacht.shouldNotBeNull()
        // The first floor's first slot reads "23:00-02:00"; the venue publishes no doors or start time.
        klubnacht.startTime shouldBe LocalTime.of(23, 0)
        klubnacht.eventDate shouldBe LocalDate.of(2026, 8, 1)
        klubnacht.sourceId shouldBe "tresor:20260801-tresor-klubnacht"
    }

    @Test
    fun `reads the poster at the size og-image names, falling back to the page's own image`() {
        scrape("tresor-detail-klubnacht.html", "20260801-tresor-klubnacht").shouldNotBeNull().imageUrl shouldBe
            "https://tresorberlin.com/wp-content/uploads/2026/06/aspiration-180x140-oil-on-canvas-2024-ch-e1782818421888-1536x1536.jpg"

        val url = "https://tresorberlin.com/event/20260801-tresor-klubnacht/"
        val withoutOpenGraph =
            javaClass.classLoader
                .getResourceAsStream("scraper/tresor/tresor-detail-klubnacht.html")!!
                .bufferedReader()
                .readText()
                .replace("og:image", "og:removed")
        scraper.scrape(Jsoup.parse(withoutOpenGraph, url), url).shouldNotBeNull().imageUrl shouldBe
            "https://tresorberlin.com/wp-content/uploads/2026/06/aspiration-180x140-oil-on-canvas-2024-ch-e1782818421888-2880x2882.jpg"
    }

    @Test
    fun `reads the act out of a room-and-format label and drops its quoted release`() {
        // "Globus Listening Session: The Fear Ratio 'Slinky'": Globus is the room, Listening
        // Session the format, Slinky the record (#1133).
        val night = scrape("tresor-detail-globus-session.html", "20260905-tresor-invites-o-v-r")
        night.shouldNotBeNull()

        val globus = night.artists.filter { it.stage == "Globus" }.map { it.name }
        globus.first() shouldBe "The Fear Ratio"
        night.artists.map { it.name } shouldContain "Samuel Kerridge"
        night.artists.none { it.name.contains("Listening Session", ignoreCase = true) } shouldBe true
    }

    @Test
    fun `keeps the blurb and cuts the standing policy text`() {
        val klubnacht = scrape("tresor-detail-klubnacht.html", "20260801-tresor-klubnacht")
        klubnacht.shouldNotBeNull()
        klubnacht.description.shouldNotBeNull() shouldStartWith "Due to personal reasons"
        // Everything below the underscore rule is repeated verbatim on every night.
        klubnacht.description.shouldNotBeNull() shouldNotContain "Guest Information"
        klubnacht.description.shouldNotBeNull() shouldNotContain "self-service lockers"
    }

    @Test
    fun `reads a genuine event blurb`() {
        val aquabahn = scrape("tresor-detail-blurb.html", "20260807-tresor-aquabahn-x-mechatronica")
        aquabahn.shouldNotBeNull()
        aquabahn.description.shouldNotBeNull() shouldStartWith "Aquabahn"
        aquabahn.startTime shouldBe LocalTime.of(23, 0)
    }

    @Test
    fun `leaves the start time null when the page publishes no set times`() {
        val singularity = scrape("tresor-detail-no-times.html", "20260803-singularity")
        singularity.shouldNotBeNull()
        singularity.startTime.shouldBeNull()
        singularity.eventDate shouldBe LocalDate.of(2026, 8, 3)
    }

    @Test
    fun `ignores the whole programme the page repeats in its footer`() {
        // Every event page lists all 30 upcoming nights below its own section, in the same markup
        // the listing uses — an unscoped read filed the entire month's lineup under one night.
        val event = scrape("tresor-detail-no-times.html", "20260803-singularity")
        event.shouldNotBeNull()
        event.artists shouldBe emptyList()
    }

    @Test
    fun `returns null for a page without a title`() {
        val url = "https://tresorberlin.com/event/20260801-x/"
        scraper.scrape(Jsoup.parse("<html><head></head><body></body></html>", url), url).shouldBeNull()
    }

    @Test
    fun `gives each act its slot's start and end, across midnight and per floor`() {
        val klubnacht = scrape("tresor-detail-klubnacht.html", "20260801-tresor-klubnacht").shouldNotBeNull()
        val sets = klubnacht.artists.associate { it.name to (it.setStart to it.setEnd) }

        // "23:00-02:00" on Saturday 1 August runs into Sunday.
        sets.getValue("Hypnotic Black Magic") shouldBe (Instant.parse("2026-08-01T21:00:00Z") to Instant.parse("2026-08-02T00:00:00Z"))
        // After the unannounced "???" slot, "04:30 – 07:30" is Sunday morning, en dash and all.
        sets.getValue("Developer") shouldBe (Instant.parse("2026-08-02T02:30:00Z") to Instant.parse("2026-08-02T05:30:00Z"))
        // "07:30-END" has a start and no end.
        sets.getValue("Maedon") shouldBe (Instant.parse("2026-08-02T05:30:00Z") to null)
        // The Globus floor opens at "00:00", which is already Sunday.
        sets.getValue("Janina") shouldBe (Instant.parse("2026-08-01T22:00:00Z") to Instant.parse("2026-08-02T00:30:00Z"))
    }

    @Test
    fun `leaves an act without a slot time untimed`() {
        val night = scrape("tresor-detail-blurb.html", "20260807-tresor-aquabahn-x-mechatronica").shouldNotBeNull()

        night.artists
            .first { it.name.startsWith("Daniel Boubet") }
            .setStart
            .shouldBeNull()
        night.artists.first { it.name == "Aura Nox" }.setStart shouldBe Instant.parse("2026-08-07T21:00:00Z")
    }

    @Test
    fun `rejects the home page a dead event link redirects to`() {
        // Tresor answers every event URL with a 302 to its home page (#2000); the listing's data must stand.
        scrape("tresor-home.html", "20261010-tresor-klubnacht").shouldBeNull()
    }
}
