package de.norm.events.scraper.quatsch

import de.norm.events.scraper.ScrapedArtist
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class QuatschCalendarScraperTest {
    private val scraper = QuatschCalendarScraper()

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/quatsch/$name")!!
            .bufferedReader()
            .readText()

    private fun day(name: String) = scraper.scrapeDay(fixture("quatsch-day-$name.json"))

    @Test
    fun `reads the plugin's settings off the tickets page`() {
        val calendar = scraper.calendar(Jsoup.parse(fixture("quatsch-tickets.html"), "https://quatsch-comedy-club.de/tickets/")).shouldNotBeNull()

        calendar.ajaxUrl shouldBe "https://quatsch-comedy-club.de/wp-admin/admin-ajax.php"
        calendar.nonce shouldBe "a732979e64"
        calendar.city shouldBe "Berlin"
        calendar.days shouldHaveSize 66
        calendar.days.first() shouldBe LocalDate.of(2026, 10, 3)
        calendar.days shouldBe calendar.days.sorted()
    }

    @Test
    fun `a page without the settings has no calendar`() {
        scraper.calendar(Jsoup.parse("<html><body><p>Wartung</p></body></html>")).shouldBeNull()
    }

    @Test
    fun `maps a house show with its comedians, its host, doors and the shop link`() {
        val shows = day("house-shows")

        shows shouldHaveSize 2
        val live = shows.first()
        live.title shouldBe "Die Live Show"
        live.eventType shouldBe "COMEDY"
        live.eventDate shouldBe LocalDate.of(2026, 10, 4)
        live.startTime shouldBe LocalTime.of(17, 0)
        live.doorsTime shouldBe LocalTime.of(16, 0)
        live.sourceId shouldBe "quatsch:eventim-2242"
        live.ticketUrl shouldBe "https://quatsch-comedy-club.eventim-inhouse.de/webshop/webticket/shop?event=2242"
        live.sourceUrl shouldBe live.ticketUrl
        live.imageUrl shouldBe "https://quatsch-comedy-club.de/wp-content/uploads/2024/11/Die-Live-Show-QCC-400x400px-web2.jpg"
        live.description shouldBe "Das Original der Mix Shows: jedes Mal neu, immer hundert Prozent Quatsch."
        live.artists shouldContainExactly
            listOf(
                ScrapedArtist("Jan Preuß"),
                ScrapedArtist("Juri von Stavenhagen"),
                ScrapedArtist("Sebastian Humi"),
                ScrapedArtist("C. Heiland"),
                ScrapedArtist("Christin Jugsch", role = "SUPPORT")
            )
    }

    @Test
    fun `a newcomer night names only its host, who is billed`() {
        val hotShot = day("house-shows")[1]

        hotShot.title shouldBe "Quatsch Comedy Hot Shot Berlin"
        hotShot.artists shouldContainExactly listOf(ScrapedArtist("Kalle Zilske"))
        hotShot.doorsTime shouldBe LocalTime.of(19, 30)
    }

    @Test
    fun `the open mic runs in the bar, with no comedians named`() {
        val openMic = day("open-mic").single { it.title.contains("Open Mic") }

        openMic.title shouldBe "Open Mic in der BAR92"
        openMic.room shouldBe "BAR92"
        openMic.artists.shouldBeEmpty()
        openMic.doorsTime shouldBe LocalTime.of(20, 0)
        openMic.description.shouldNotBeNull() shouldContain "Seiteneingang (Ziegelstraße)"
    }

    @Test
    fun `a guest show is keyed by date and title, bills its act and links its outside seller`() {
        val guest = day("guest-show").single()

        guest.title shouldBe "Filippo Giardina - La Banalità Del Male (IT)"
        guest.sourceId shouldBe "quatsch:2026-10-06-filippo-giardina-la-banalita-del-male-it"
        guest.ticketUrl shouldBe "https://gotobeat.com/gig/filippo-giardina-la-banalit-del-male-berlino-8w"
        guest.sourceUrl shouldBe guest.ticketUrl
        guest.artists.map { it.name } shouldContainExactly listOf("Filippo Giardina")
        guest.startTime shouldBe LocalTime.of(19, 30)
        // "Ingresso/Inizio" is doors and start in one, so no separate doors time.
        guest.doorsTime.shouldBeNull()
        guest.description.shouldNotBeNull() shouldContain "comico e autore satirico"
    }

    @Test
    fun `reads a doors time given in the evening's twelve-hour form`() {
        val late = day("late-guest-show").single { it.title.startsWith("Led Varela") }

        late.startTime shouldBe LocalTime.of(23, 0)
        late.doorsTime shouldBe LocalTime.of(22, 30)
    }

    @Test
    fun `reads a start time sent as an array, and drops the seller's tracking parameter`() {
        val zymny = day("time-array").single()

        zymny.startTime shouldBe LocalTime.of(20, 0)
        zymny.doorsTime shouldBe LocalTime.of(19, 0)
        zymny.ticketUrl.shouldNotBeNull() shouldNotContain "srsltid"
    }

    @Test
    fun `a day without a show, or an answer that is not JSON, yields nothing`() {
        day("no-show").shouldBeEmpty()
        scraper.scrapeDay("<html>").shouldBeEmpty()
    }
}
