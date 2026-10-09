package de.norm.events.scraper.silentgreen

import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.alternateLanguageUrl
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
    fun `reads the English version's blurb without its credit line`() {
        val path = "programm/detail/scribblemp3-pres-felisha-ledesma-angelo-harmsworth-francesco-corvi-nocturnerror"
        val german = parse("silentgreen-detail-ledesma-de.html").shouldNotBeNull()
        german.description.shouldNotBeNull() shouldStartWith "Felisha Ledesma & Angelo Harmsworth"

        val englishHtml =
            javaClass.classLoader
                .getResourceAsStream("scraper/silentgreen/silentgreen-detail-ledesma-en.html")!!
                .bufferedReader()
                .readText()
        val englishUrl = "https://www.silent-green.net/en/programme/detail/${path.substringAfter("programm/detail/")}"
        val germanHtml =
            javaClass.classLoader
                .getResourceAsStream("scraper/silentgreen/silentgreen-detail-ledesma-de.html")!!
                .bufferedReader()
                .readText()
        Jsoup.parse(germanHtml, "https://www.silent-green.net/$path").alternateLanguageUrl("en") shouldBe englishUrl
        val english = scraper.description(Jsoup.parse(englishHtml, englishUrl)).shouldNotBeNull()
        english shouldStartWith "Felisha Ledesma & Angelo Harmsworth"
        english shouldContain "come together in a collaboration"
        english shouldNotContain "silent green presents"
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
    fun `scrape reads a festival's daily hours from the blurb`() {
        val details = parse("silentgreen-detail-festival-hours.html").shouldNotBeNull()

        details.startTime.shouldBeNull()
        // The highlights' "10. Oktober / 11 bis 18 Uhr" is one screening, not the opening hours.
        details.dailyHours shouldBe
            mapOf(
                LocalDate.of(2026, 10, 8) to SilentGreenOpeningHours(LocalTime.of(19, 0), LocalTime.of(22, 0)),
                LocalDate.of(2026, 10, 9) to SilentGreenOpeningHours(LocalTime.of(11, 0), LocalTime.of(22, 0)),
                LocalDate.of(2026, 10, 10) to SilentGreenOpeningHours(LocalTime.of(11, 0), LocalTime.MIDNIGHT),
                LocalDate.of(2026, 10, 11) to SilentGreenOpeningHours(LocalTime.of(11, 0), LocalTime.MIDNIGHT)
            )
    }

    @Test
    fun `scrape reads minutes, a hyphen, a day range and a month after the date block's`() {
        val document =
            Jsoup.parse(
                """<div class="news-detail">
                  <span class="event-detail-date-begin">Mi. 30.12.2026 -</span>
                  <div class="ce-bodytext"><p><strong>30.–31. Dezember: 18:30-23 Uhr</strong><br>
                  <strong>2. Januar: 12-24:00 Uhr</strong></p></div></div>""",
                "https://www.silent-green.net/programm/detail/x"
            )

        scraper.scrape(document).shouldNotBeNull().dailyHours shouldBe
            mapOf(
                LocalDate.of(2026, 12, 30) to SilentGreenOpeningHours(LocalTime.of(18, 30), LocalTime.of(23, 0)),
                LocalDate.of(2026, 12, 31) to SilentGreenOpeningHours(LocalTime.of(18, 30), LocalTime.of(23, 0)),
                LocalDate.of(2027, 1, 2) to SilentGreenOpeningHours(LocalTime.NOON, LocalTime.MIDNIGHT)
            )
    }

    @Test
    fun `applyTo fills a festival day from its daily hours, ending past midnight on the next day`() {
        val details =
            SilentGreenEventDetails(
                dailyHours =
                    mapOf(
                        LocalDate.of(2026, 10, 8) to SilentGreenOpeningHours(LocalTime.of(19, 0), LocalTime.of(22, 0)),
                        LocalDate.of(2026, 10, 10) to SilentGreenOpeningHours(LocalTime.of(11, 0), LocalTime.MIDNIGHT)
                    )
            )

        fun day(
            date: LocalDate,
            start: LocalTime? = null
        ) = ScrapedEvent(
            title = "9. Festival of Animation Berlin 2026",
            eventType = "FESTIVAL",
            eventDate = date,
            startTime = start,
            sourceUrl = "https://www.silent-green.net/programm/detail/9-festival-of-animation-berlin-2026",
            sourceId = "silent_green:$date-9-festival-of-animation-berlin-2026"
        )

        val thursday = details.applyTo(day(LocalDate.of(2026, 10, 8)))
        thursday.startTime shouldBe LocalTime.of(19, 0)
        thursday.endDate shouldBe LocalDate.of(2026, 10, 8)
        thursday.endTime shouldBe LocalTime.of(22, 0)

        val saturday = details.applyTo(day(LocalDate.of(2026, 10, 10)))
        saturday.startTime shouldBe LocalTime.of(11, 0)
        saturday.endDate shouldBe LocalDate.of(2026, 10, 11)
        saturday.endTime shouldBe LocalTime.MIDNIGHT

        // A time the calendar row prints wins, and the blurb's end does not pair with it.
        val printed = details.applyTo(day(LocalDate.of(2026, 10, 8), start = LocalTime.of(20, 0)))
        printed.startTime shouldBe LocalTime.of(20, 0)
        printed.endDate.shouldBeNull()
        printed.endTime.shouldBeNull()

        // So does the page's own start time.
        val paged = details.copy(startTime = LocalTime.of(18, 0)).applyTo(day(LocalDate.of(2026, 10, 8)))
        paged.startTime shouldBe LocalTime.of(18, 0)
        paged.endTime.shouldBeNull()
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
