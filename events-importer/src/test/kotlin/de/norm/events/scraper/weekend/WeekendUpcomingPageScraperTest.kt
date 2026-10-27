package de.norm.events.scraper.weekend

import de.norm.events.event.ArtistRole
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

class WeekendUpcomingPageScraperTest {
    private val sourceUrl = "https://www.weekendclub.berlin/upcoming"
    private val scraper = WeekendUpcomingPageScraper()

    private val events by lazy {
        scraper.scrape(
            Jsoup.parse(
                javaClass.classLoader
                    .getResourceAsStream("scraper/weekend/weekend-upcoming.html")!!
                    .bufferedReader()
                    .readText(),
                sourceUrl
            ),
            sourceUrl
        )
    }

    private fun event(title: String) = events.first { it.title == title }

    private fun djs(title: String) = event(title).artists.map { it.name }

    @Test
    fun `reads the upcoming nights and leaves out the past ones`() {
        events shouldHaveSize 14
        events.minOf { it.eventDate } shouldBe LocalDate.of(2026, 10, 16)
        events.maxOf { it.eventDate } shouldBe LocalDate.of(2026, 12, 31)
        events.map { it.sourceId }.distinct() shouldHaveSize 14
    }

    @Test
    fun `maps a night with its overnight end, ticket shop and DJs`() {
        val operator = event("WKND X OPERATOR")

        operator.eventType shouldBe "PARTY"
        operator.eventDate shouldBe LocalDate.of(2026, 11, 13)
        operator.startTime shouldBe LocalTime.of(22, 0)
        operator.endDate shouldBe LocalDate.of(2026, 11, 14)
        operator.endTime shouldBe LocalTime.of(6, 0)
        operator.doorsTime.shouldBeNull()
        operator.sourceUrl shouldBe "https://www.weekendclub.berlin/upcoming/d83xspd4cy8bk5d-ng5ha"
        operator.sourceId shouldBe "weekend:d83xspd4cy8bk5d-ng5ha"
        operator.ticketUrl shouldBe "https://xceed.me/en/berlin/event/wknd-x-operator-2/243252/channel/weekend-club-berlin"
        operator.imageUrl!! shouldStartWith "https://images.squarespace-cdn.com/"
        operator.description!! shouldStartWith "🖤 WKND X OPERATOR\n"
        operator.description shouldContain "Hosted by Dennis Beutler"
        operator.artists.map { it.name to it.role } shouldContainExactly listOf("Dennis Beutler" to ArtistRole.DJ)
    }

    @Test
    fun `keeps a dated slug whole`() {
        event("80'S & 90'S PARTY VOL. 2").sourceId shouldBe "weekend:2026/10/8/80s-90s-party-vol-2"
    }

    @Test
    fun `reads a DJ line-up paragraph`() {
        djs("80'S & 90'S PARTY VOL. 2") shouldContainExactly listOf("Bat", "DEGREE", "Rokit")
    }

    @Test
    fun `reads the line-up of each room`() {
        djs("NEW YEAR'S EVE 2026/27 – ROOFTOP OVER BERLIN") shouldContainExactly
            listOf("Harris", "Gallus", "Rokit", "Dennis Beutler", "PayPaul", "Miguel", "Jacques Dupont")
    }

    @Test
    fun `reads a DJs line and drops its continuation`() {
        djs("PORNFILM FESTIVAL AFTERPARTY") shouldContainExactly
            listOf("pomoZ", "JayCap", "Ivo Bischoff", "Sara Moshiri", "DÖRTE", "SONY STRAIGHT")
    }

    @Test
    fun `bills the members of a crew`() {
        djs("WKND X THE MICHELINS") shouldContainExactly listOf("Phonique", "Misha Svirid")
    }

    @Test
    fun `a line-up of styles bills nobody`() {
        event("LAtin FIESTA").artists.shouldBeEmpty()
        event("LAtin FIESTA").ticketUrl.shouldBeNull()
    }

    @Test
    fun `a night without a line-up paragraph bills nobody`() {
        event("WKND X STUCK IN SPACE – PRE HALLOWEEN").artists.shouldBeEmpty()
    }

    @Test
    fun `a page without events yields nothing`() {
        scraper.scrape(Jsoup.parse("<html><body><p>Nothing planned</p></body></html>", sourceUrl), sourceUrl).shouldBeEmpty()
    }
}
