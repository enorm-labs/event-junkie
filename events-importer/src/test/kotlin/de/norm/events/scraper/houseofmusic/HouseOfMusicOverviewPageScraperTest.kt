package de.norm.events.scraper.houseofmusic

import de.norm.events.event.EventType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class HouseOfMusicOverviewPageScraperTest {
    private val baseUrl = "https://www.houseofmusic.berlin/"
    private val events = HouseOfMusicOverviewPageScraper().scrape(Jsoup.parse(fixture(), baseUrl), baseUrl)

    private fun titled(title: String) = events.first { it.title == title }

    @Test
    fun `reads every event from the Wix warmup payload in Berlin time`() {
        events.size shouldBe 10
        events.first().eventDate shouldBe LocalDate.of(2026, 10, 8)
        events.last().eventDate shouldBe LocalDate.of(2026, 11, 1)
        val concert = titled("Ragde Lobo Medicine Music Concert")
        concert.eventType shouldBe EventType.CONCERT.name
        concert.startTime shouldBe LocalTime.of(19, 0)
        concert.endDate shouldBe LocalDate.of(2026, 10, 11)
        concert.endTime shouldBe LocalTime.of(23, 0)
        concert.sourceId shouldBe "house_of_music:ragde-lobo-medicine-music-concert"
        concert.sourceUrl shouldBe baseUrl
        concert.ticketUrl shouldBe "https://rausgegangen.de/en/events/medicine-music-konzert-mit-ragde-lobo-0/"
        events.first().endTime shouldBe null
    }

    @Test
    fun `types the quiz and bills no act for it`() {
        val quizzes = events.filter { it.title == "House of Music Quiz" }

        quizzes.map { it.sourceId } shouldBe listOf("house_of_music:house-of-music-quiz-44", "house_of_music:house-of-music-quiz-45")
        quizzes.forEach {
            it.eventType shouldBe EventType.QUIZ.name
            it.artists.shouldBeEmpty()
            it.ticketUrl shouldBe null
        }
    }

    @Test
    fun `keeps an Instagram link out of the ticket link`() {
        titled("Jonny Mahoro | Album Pre Event").ticketUrl shouldBe null
    }

    @Test
    fun `reads the acts from the house's title shapes`() {
        titled("Jonny Mahoro | Album Pre Event").artists.map { it.name } shouldBe listOf("Jonny Mahoro")
        titled("JAZZTICK - Undertale Concert Determination").artists.map { it.name } shouldBe listOf("JAZZTICK")
        titled("Big Mama Society - Berlin Album Release Concert").artists.map { it.name } shouldBe listOf("Big Mama Society")
        titled("Madcat Records: AI Girlfriend; Leo Trovato; In:Numbers").artists.map { it.name } shouldBe
            listOf("AI Girlfriend", "Leo Trovato", "In:Numbers")
        titled("Gris Festival | Grey River & The Smoky Mountain + Lia Hide").artists.map { it.name } shouldBe
            listOf("Grey River & The Smoky Mountain", "Lia Hide")
        titled("The Sauce | Jam Session feat. DAR!O").artists.map { it.name } shouldBe listOf("DAR!O")
        titled("Jonny Mahoro | Album Pre Event").artists.single().titleDerived shouldBe true
        titled("Ragde Lobo Medicine Music Concert").artists.shouldBeEmpty()
    }

    @Test
    fun `marks a listed lineup as not title-derived, so a festival night keeps its acts`() {
        titled("Gris Festival | Grey River & The Smoky Mountain + Lia Hide").artists.map { it.titleDerived } shouldBe listOf(false, false)
        titled("Madcat Records: AI Girlfriend; Leo Trovato; In:Numbers").artists.map { it.titleDerived } shouldBe listOf(false, false, false)
    }

    @Test
    fun `yields nothing for a page without the warmup payload`() {
        HouseOfMusicOverviewPageScraper().scrape(Jsoup.parse("<html><body></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }

    private fun fixture(): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/houseofmusic/houseofmusic-overview.html")!!
            .bufferedReader()
            .readText()
}
