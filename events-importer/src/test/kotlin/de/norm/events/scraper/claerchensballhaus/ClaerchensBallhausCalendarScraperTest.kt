package de.norm.events.scraper.claerchensballhaus

import de.norm.events.event.EventType
import de.norm.events.scraper.isForChildren
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

private const val BASE_URL = "https://claerchensball.haus/programm/"

/** The `/programm/` page saved on 2026-10-23. */
fun claerchensCalendar(): Document =
    Jsoup.parse(
        ClaerchensBallhausCalendarScraperTest::class.java.classLoader
            .getResourceAsStream("scraper/claerchensballhaus/claerchensballhaus-overview.html")!!
            .bufferedReader()
            .readText(),
        BASE_URL
    )

class ClaerchensBallhausCalendarScraperTest {
    private val events = ClaerchensBallhausCalendarScraper().scrape(claerchensCalendar(), BASE_URL)

    private fun event(sourceId: String) = events.single { it.sourceId == "claerchens_ballhaus:$sourceId" }

    @Test
    fun `keeps every in-scope occurrence, each under its own key`() {
        events shouldHaveSize 99
        events.map { it.sourceId }.toSet() shouldHaveSize 99
        events.filterNot { it.isForChildren } shouldHaveSize 97
    }

    @Test
    fun `reads a guest concert fully`() {
        val concert = event("2026-12-06-2000-musik-der-welt-tango-tenors")

        concert.title shouldBe "Tango Tenors"
        concert.eventType shouldBe EventType.CONCERT.name
        concert.eventDate shouldBe LocalDate.of(2026, 12, 6)
        concert.startTime shouldBe LocalTime.of(20, 0)
        concert.sourceUrl shouldBe "https://claerchensball.haus/musik-der-welt-tango-tenors/"
        concert.ticketUrl shouldBe "https://www.yesticket.org/event/en/tango-tenors-06-12-26/"
        concert.imageUrl!! shouldEndWith "tango-tenors-duo-clean-819x1024.jpg"
        concert.artists.map { it.name } shouldContainExactly listOf("Tango Tenors")
    }

    @Test
    fun `bills a concert's act without the Konzert suffix or a voice type`() {
        event("2026-11-12-1900-donata-konzert").artists.map { it.name } shouldContainExactly listOf("Donata")
        event("2027-02-10-1939-susanna-hurrell-soprano-songs-by-candlelight").artists.map { it.name } shouldContainExactly listOf("Susanna Hurrell")
        event("2026-11-08-1700-sonntagskonzert-im-spiegelsaal-10").artists.shouldBeEmpty()
    }

    @Test
    fun `bills no act for the children's and family concerts, and still bills a guest whose title says Konzert`() {
        event("2026-11-08-1200-sonntagskonzert-im-spiegelsaal-9").artists.shouldBeEmpty()
        event("2026-12-06-1400-familienkonzert-im-spiegelsaal").artists.shouldBeEmpty()
        event("2026-05-07-2000-berlin-session-strings-konzert-2").artists.map { it.name } shouldContainExactly listOf("Berlin Session Strings")
        event("2026-06-25-1900-reflections-viva-la-musica-konzert").artists.shouldNotBeEmpty()
    }

    @Test
    fun `types a class followed by a dance night as a party, and keeps the schedule as its text`() {
        val salsa = event("2026-11-02-1900-salsa")

        salsa.eventType shouldBe EventType.PARTY.name
        salsa.description shouldBe "Tanzkurs: 19:00 - 20:15\nTanzabend: 20:15 - 0:00"
        salsa.artists.shouldBeEmpty()
        event("2026-11-04-1830-tango").eventType shouldBe EventType.PARTY.name
    }

    @Test
    fun `types a slam or a salon as a reading, and a night with no cue as other`() {
        event("2026-11-01-2000-kiezpoeten-clarchens-crime-slam").eventType shouldBe EventType.READING.name
        event("2026-10-23-1930-salon-fahig-teamspirit-oder-eusozialitat-bei-insekten").eventType shouldBe EventType.READING.name
        val maBaker = event("2026-10-31-2000-ma-baker-2")
        maBaker.eventType shouldBe EventType.OTHER.name
        maBaker.typeIsFallback shouldBe true
    }

    @Test
    fun `takes a ticket link only from a ticket shop`() {
        event("2026-11-03-1915-swing").ticketUrl.shouldBeNull()
        event("2026-11-08-1700-sonntagskonzert-im-spiegelsaal-10").ticketUrl.shouldBeNull()
    }

    @Test
    fun `drops the history talk, workshops, the wine dinner and the sing-along, and marks the children's concerts`() {
        val titles = events.map { it.title }

        titles shouldNotContain "Vortrag über Clärchens Geschichte im Spiegelsaal"
        titles shouldNotContain "Swing Workshop im Spiegelsaal"
        titles shouldNotContain "Weinmahleins"
        titles shouldNotContain "YesChoir!"
        events.filter { it.isForChildren }.map { it.title }.shouldContainExactlyInAnyOrder("Kinderkonzert im Spiegelsaal", "Familienkonzert im Spiegelsaal")
    }

    // The entries for 2026-11-15 and 2026-12-13 carry no text, and both were stored as concerts.
    @Test
    fun `drops a YesChoir entry that carries no text`() {
        val page =
            """<script>var c = { eventSources: [[{"title":"YesChoir!","start":"2026-11-15T16:30:00","details":"","permalink":"https://claerchensball.haus/yeschoir-2/"}]] };</script>"""

        ClaerchensBallhausCalendarScraper().scrape(Jsoup.parse(page, BASE_URL), BASE_URL).shouldBeEmpty()
    }

    @Test
    fun `returns nothing for a page without the calendar`() {
        ClaerchensBallhausCalendarScraper().scrape(Jsoup.parse("<script>let x = 1;</script>", BASE_URL), BASE_URL).shouldBeEmpty()
    }
}
