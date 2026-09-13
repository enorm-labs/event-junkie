package de.norm.events.scraper.silentgreen

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.nulls.shouldBeNull
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
 * Unit tests for [SilentGreenDetailPageScraper], parsing saved snapshots of a concert, an
 * exhibition and a festival detail page.
 */
class SilentGreenDetailPageScraperTest {
    private val scraper = SilentGreenDetailPageScraper()

    private fun parse(fixture: String): SilentGreenEventDetails? {
        val url = "https://www.silent-green.net/programm/detail/htrk"
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/silentgreen/$fixture")!!
                .bufferedReader()
                .readText()
        return scraper.scrape(Jsoup.parse(html, url))
    }

    @Test
    fun `scrape reads the doors and start times a concert page labels in German`() {
        val details = parse("silentgreen-detail-konzert.html").shouldNotBeNull()

        details.doorsTime shouldBe LocalTime.of(19, 0)
        details.startTime shouldBe LocalTime.of(19, 45)
    }

    @Test
    fun `scrape takes the poster from og-image rather than the responsive carousel`() {
        val details = parse("silentgreen-detail-konzert.html").shouldNotBeNull()

        details.imageUrl shouldBe "https://www.silent-green.net/fileadmin/user_upload/veranstaltungen/2026/08_HTRK/13245.jpeg"
    }

    @Test
    fun `scrape joins the blurb and drops the credit line it opens with`() {
        val details = parse("silentgreen-detail-konzert.html").shouldNotBeNull()
        val description = details.description.shouldNotBeNull()

        description shouldStartWith "HTRK"
        description shouldContain "Loraine James"
        // "Berlin Atonal & silent green präsentieren" is already stored as the event's promoters.
        description shouldNotContain "präsentieren"
    }

    @Test
    fun `scrape reads an exhibition page that carries no times`() {
        val details = parse("silentgreen-detail-ausstellung.html").shouldNotBeNull()

        details.doorsTime.shouldBeNull()
        details.startTime.shouldBeNull()
        // The date block "Fr. 17.07.2026 – So. 23.08.2026" is the run (ADR-029).
        details.runStart shouldBe LocalDate.of(2026, 7, 17)
        details.runEnd shouldBe LocalDate.of(2026, 8, 23)
        details.imageUrl shouldBe "https://www.silent-green.net/fileadmin/_processed_/0/d/csm_NEU_melhus_36e09c3bc3.png"
        details.description.shouldNotBeNull() shouldContain "Krisenmodus"
    }

    @Test
    fun `scrape reads a festival page spanning several days`() {
        val details = parse("silentgreen-detail-festival.html").shouldNotBeNull()

        details.description.shouldNotBeNull() shouldContain "Pop-Kultur Festival"
        details.imageUrl.shouldNotBeNull() shouldContain "pk26_talks_silentgreen"
        details.runStart shouldBe LocalDate.of(2026, 8, 24)
        details.runEnd shouldBe LocalDate.of(2026, 8, 26)
    }

    @Test
    fun `scrape reads a single day as a start with no end`() {
        val details = parse("silentgreen-detail-konzert.html").shouldNotBeNull()

        details.runStart shouldBe LocalDate.of(2026, 8, 2)
        details.runEnd.shouldBeNull()
    }

    @Test
    fun `scrape returns null when the page carries none of the run-level fields`() {
        val document = Jsoup.parse("<html><body><p>Seite nicht gefunden</p></body></html>", "https://www.silent-green.net/x")

        scraper.scrape(document).shouldBeNull()
    }

    @Test
    fun `applyTo fills only what the calendar row left empty`() {
        val row =
            ScrapedEvent(
                title = "HTRK + Loraine James",
                eventDate = LocalDate.of(2026, 8, 2),
                startTime = LocalTime.of(19, 45),
                sourceUrl = "https://www.silent-green.net/programm/detail/htrk",
                sourceId = "silent_green:2026-08-02-htrk"
            )
        val details =
            SilentGreenEventDetails(
                doorsTime = LocalTime.of(19, 0),
                startTime = LocalTime.of(18, 0),
                description = "Blurb",
                imageUrl = "https://www.silent-green.net/poster.jpg"
            )

        val merged = details.applyTo(row)

        // The calendar row is the per-day truth: its own start time survives the run's page.
        merged.startTime shouldBe LocalTime.of(19, 45)
        merged.doorsTime shouldBe LocalTime.of(19, 0)
        merged.description shouldBe "Blurb"
        merged.imageUrl shouldBe "https://www.silent-green.net/poster.jpg"
    }

    @Test
    fun `applyTo gives an exhibition the page's span, and a concert its own day`() {
        val details = SilentGreenEventDetails(runStart = LocalDate.of(2026, 7, 17), runEnd = LocalDate.of(2026, 8, 23))

        fun row(type: String) =
            ScrapedEvent(
                title = "Bjørn Melhus: LOST IN FINITY",
                eventType = type,
                eventDate = LocalDate.of(2026, 8, 14),
                sourceUrl = "https://www.silent-green.net/programm/detail/bjoern-melhus-lost-in-finity",
                sourceId = "silent_green:2026-08-14-bjoern-melhus-lost-in-finity"
            )

        val exhibition = details.applyTo(row("EXHIBITION"))
        exhibition.eventDate shouldBe LocalDate.of(2026, 7, 17)
        exhibition.endDate shouldBe LocalDate.of(2026, 8, 23)

        // A festival's page has a span too, and its days keep their own dates.
        val festivalDay = details.applyTo(row("FESTIVAL"))
        festivalDay.eventDate shouldBe LocalDate.of(2026, 8, 14)
        festivalDay.endDate.shouldBeNull()
    }
}
