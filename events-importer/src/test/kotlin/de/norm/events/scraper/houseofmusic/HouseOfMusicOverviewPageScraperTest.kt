package de.norm.events.scraper.houseofmusic

import de.norm.events.event.ArtistRole
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

    private val later =
        HouseOfMusicOverviewPageScraper().scrape(Jsoup.parse(fixture("houseofmusic-overview-2026-10-20.html"), baseUrl), baseUrl)

    private fun titled(title: String) = events.first { it.title == title }

    private fun titledLater(title: String) = later.first { it.title == title }

    private fun retitled(title: String) =
        HouseOfMusicOverviewPageScraper()
            .scrape(Jsoup.parse(fixture("houseofmusic-overview-2026-10-20.html").replace("House of Music Quiz", title), baseUrl), baseUrl)
            .first { it.title.equals(title, ignoreCase = true) }

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
    fun `types a meetup and a film night by the house's words and bills no act for them`() {
        val meetup = titledLater("Ableton User Group Berlin: Track Feedback Session")
        val film = titledLater("Free DrinkWatchTalk short movie evening")

        meetup.eventType shouldBe EventType.OTHER.name
        meetup.artists.shouldBeEmpty()
        film.eventType shouldBe EventType.SCREENING.name
        film.artists.shouldBeEmpty()
    }

    @Test
    fun `reads the acts after a series name and w-slash as a lineup`() {
        val night = titledLater("jazzexzess w/ Luise Volkmann’s Punk-Jazz-Projekt + Vehicle/Passenger")

        night.eventType shouldBe EventType.CONCERT.name
        night.artists.map { it.name } shouldBe listOf("Luise Volkmann’s Punk-Jazz-Projekt", "Vehicle/Passenger")
        night.artists.map { it.titleDerived } shouldBe listOf(false, false)
    }

    @Test
    fun `bills no act for a showcase, which is a format`() {
        val showcase = retitled("LISTEN TO BERLIN: YOUTH SHOWCASE (MW:M)")

        showcase.eventType shouldBe EventType.CONCERT.name
        showcase.artists.shouldBeEmpty()
        retitled("Jonny Mahoro | Showcase").artists.map { it.name } shouldBe listOf("Jonny Mahoro")
    }

    @Test
    fun `reads the acts after a known series and feat as a lineup`() {
        val night = retitled("K:noise RAW signal feat. In Your Pocket + White Forest")

        night.eventType shouldBe EventType.CONCERT.name
        night.artists.map { it.name } shouldBe listOf("In Your Pocket", "White Forest")
        night.artists.map { it.titleDerived } shouldBe listOf(false, false)
        retitled("K:noise RAW signal w/ In Your Pocket").artists.map { it.name } shouldBe listOf("In Your Pocket")
    }

    @Test
    fun `keeps an act and its guest when the title is no known series`() {
        val night = retitled("Jonny Mahoro feat. In Your Pocket")
        val long = retitled("The Big Berlin Brass Collective and Friends feat. The Smoky Mountain Youth Choir")

        night.artists.map { it.name to it.role } shouldBe listOf("Jonny Mahoro" to ArtistRole.HEADLINER, "In Your Pocket" to ArtistRole.SUPPORT)
        long.artists.map { it.name to it.role } shouldBe
            listOf("The Big Berlin Brass Collective and Friends" to ArtistRole.HEADLINER, "The Smoky Mountain Youth Choir" to ArtistRole.SUPPORT)
    }

    @Test
    fun `keeps a concert that names a film a concert`() {
        val html = fixture().replace("JAZZTICK - Undertale Concert Determination", "JAZZTICK - Ghibli Film Concert")
        val concert = HouseOfMusicOverviewPageScraper().scrape(Jsoup.parse(html, baseUrl), baseUrl).first { it.title == "JAZZTICK - Ghibli Film Concert" }

        concert.eventType shouldBe EventType.CONCERT.name
        concert.artists.map { it.name } shouldBe listOf("JAZZTICK")
    }

    @Test
    fun `keeps the house's other shapes on the later programme`() {
        later.size shouldBe 7
        titledLater("House of Music Quiz").eventType shouldBe EventType.QUIZ.name
        titledLater("Madcat Records: AI Girlfriend; Leo Trovato; In:Numbers").artists.map { it.name } shouldBe
            listOf("AI Girlfriend", "Leo Trovato", "In:Numbers")
        titledLater("Gris Festival | Grey River & The Smoky Mountain + Lia Hide").artists.map { it.name } shouldBe
            listOf("Grey River & The Smoky Mountain", "Lia Hide")
    }

    @Test
    fun `yields nothing for a page without the warmup payload`() {
        HouseOfMusicOverviewPageScraper().scrape(Jsoup.parse("<html><body></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }

    private fun fixture(name: String = "houseofmusic-overview.html"): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/houseofmusic/$name")!!
            .bufferedReader()
            .readText()
}
