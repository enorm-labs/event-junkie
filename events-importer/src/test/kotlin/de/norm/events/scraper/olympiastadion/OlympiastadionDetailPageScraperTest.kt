package de.norm.events.scraper.olympiastadion

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Test
import java.time.LocalTime

class OlympiastadionDetailPageScraperTest {
    private val scraper = OlympiastadionDetailPageScraper()

    private fun page(name: String): Document =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/olympiastadion/$name")!!
                .bufferedReader()
                .readText(),
            "https://olympiastadion.berlin/events/x/"
        )

    @Test
    fun `reads a concert page whose start is not set yet`() {
        val detail = scraper.scrape(page("olympiastadion-detail-concert.html"))
        detail.sport shouldBe false
        detail.startTime.shouldBeNull()
        detail.doorsTime.shouldBeNull()
        detail.ticketUrl shouldBe "https://www.livenation.de/u2-tickets-adp7159"
        detail.englishUrl shouldBe "https://olympiastadion.berlin/en/events/u2-the-greatest-show-on-earth-world-tour/"
        detail.description!! shouldStartWith "U2’s „The Greatest Show On Earth“-Welttournee kommt am 30.06.2027"
    }

    @Test
    fun `files a national-team match as sport by its kick-off and league`() {
        val detail = scraper.scrape(page("olympiastadion-detail-match.html"))
        detail.sport shouldBe true
    }

    @Test
    fun `files a run as sport by its kind heading`() {
        val detail = scraper.scrape(page("olympiastadion-detail-run.html"))
        detail.sport shouldBe true
        detail.startTime shouldBe LocalTime.of(9, 30)
    }

    @Test
    fun `reads a start and a doors time`() {
        val html =
            """
            <div class="heroEvents__titles"><h1>Band</h1></div>
            <div class="heroEvents__box">
              <div class="heroEvents__box__item"><span>Beginn:</span> 19:30 Uhr</div>
              <div class="heroEvents__box__item"><span>Stadionöffnung:</span> 16:00 Uhr</div>
            </div>
            <div class="twoColTickets__left"><h2><span>Band im Olympiastadion Berlin</span></h2></div>
            """.trimIndent()
        val detail = scraper.scrape(Jsoup.parse(html))
        detail.sport shouldBe false
        detail.startTime shouldBe LocalTime.of(19, 30)
        detail.doorsTime shouldBe LocalTime.of(16, 0)
    }
}
