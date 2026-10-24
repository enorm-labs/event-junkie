package de.norm.events.scraper.pfefferbergtheater

import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.forChildrenPhrase
import de.norm.events.scraper.isForChildren
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class PfefferbergTheaterCalendarPageScraperTest {
    private val scraper = PfefferbergTheaterCalendarPageScraper()
    private val url = "https://pfefferberg-theater.de/spielplan/kalender/"

    private val events by lazy {
        scraper.scrape(
            Jsoup.parse(
                javaClass.classLoader
                    .getResourceAsStream("scraper/pfefferbergtheater/pfefferbergtheater-calendar.html")!!
                    .bufferedReader()
                    .readText(),
                url
            ),
            url
        )
    }

    private val maerchenUrl = "https://pfefferberg-theater.de/spielplan/maerchenimglaspalast26_27/"

    private val maerchen by lazy {
        scraper.scrape(
            Jsoup.parse(
                javaClass.classLoader
                    .getResourceAsStream("scraper/pfefferbergtheater/pfefferbergtheater-maerchen.html")!!
                    .bufferedReader()
                    .readText(),
                maerchenUrl
            ),
            maerchenUrl
        )
    }

    private fun event(sourceId: String) = events.single { it.sourceId == "pfefferberg_theater:$sourceId" }

    @Test
    fun `reads every performance and marks the children's theatre for-children`() {
        events shouldHaveSize 109
        events.map { it.sourceId }.distinct() shouldHaveSize events.size
        events.count { it.genre == "Kindertheater" } shouldBe 28
        events.filter { it.isForChildren }.map { it.genre }.distinct() shouldContainExactly listOf("Kindertheater")
        events.last().eventDate shouldBe LocalDate.of(2027, 11, 23)
    }

    @Test
    fun `reads FIL's sold-out show on 15 December`() {
        val fil = event("29935")
        fil.title shouldBe "Fil mit neuer Show – Winter 26/27"
        fil.subtitle shouldBe "Literatur LIVE"
        fil.eventDate shouldBe LocalDate.of(2026, 12, 15)
        fil.startTime shouldBe LocalTime.of(20, 0)
        fil.eventType shouldBe "READING"
        fil.genre shouldBe "Lesungen/Buchpremieren"
        fil.soldOut shouldBe true
        fil.status shouldBe "SCHEDULED"
        fil.sourceUrl shouldBe "https://pfefferberg-theater.de/spielplan/kalender/lesung-fil-01/"
        fil.artists shouldContainExactly listOf(ScrapedArtist(name = "Fil", titleDerived = true))
        fil.isForChildren shouldBe false
    }

    @Test
    fun `marks a children's show by the house's category`() {
        events.first { it.title == "Der himmlische Zirkus" }.forChildrenPhrase shouldBe "Kindertheater"
    }

    @Test
    fun `reads the Märchen im Glaspalast page into the Glaspalast`() {
        maerchen shouldHaveSize 92
        maerchen.all { it.room == "Glaspalast" && it.eventType == "SHOW" } shouldBe true
        maerchen.single { it.sourceId == "pfefferberg_theater:33698" }.run {
            title shouldBe "Spielzeiteröffnung mit Premieren: Der Tannenbaum | Das Mädchen mit den Schwefelhölzern"
            subtitle shouldBe "Hexenberg Ensemble"
            eventDate shouldBe LocalDate.of(2026, 11, 13)
            startTime shouldBe LocalTime.of(19, 30)
            artists.shouldBeEmpty()
        }
        events.none { it.room != null } shouldBe true
    }

    @Test
    fun `cancels a performance the title marks ABGESAGT and drops the marks`() {
        val dracula = event("31069")
        dracula.status shouldBe "CANCELLED"
        dracula.title shouldBe "Dracula – A Comedy of Terrors"
        dracula.soldOut shouldBe false
    }

    @Test
    fun `drops a premiere note from the title`() {
        events.filter { it.title.startsWith("SHROUDED") }.forEach { it.title shouldBe "SHROUDED" }
    }

    @Test
    fun `types each row by the house's category`() {
        events.first { it.title == "Comedy Slam Berlin" }.eventType shouldBe "COMEDY"
        events.first { it.title == "Barbaren Barbies" }.run {
            eventType shouldBe "SHOW"
            genre shouldBe "Neuer Zirkus"
        }
        events.first { it.title.startsWith("Constantin Schreiber") }.typeIsFallback shouldBe true
        events.none { it.genre.orEmpty().contains("Studis") } shouldBe true
    }

    @Test
    fun `bills the readers a reading names and no one for a production`() {
        fun acts(prefix: String) = events.first { it.title.startsWith(prefix) }.artists.map { it.name }

        acts("Julia Ebner") shouldContainExactly listOf("Julia Ebner", "Fikri Anıl Altıntaş")
        acts("Dr. med. Julia Fischer") shouldContainExactly listOf("Julia Fischer")
        acts("Eva zu Beck") shouldContainExactly listOf("Eva zu Beck")
        acts("Jane Austen") shouldContainExactly listOf("Bianca Nawrath", "Judith Poznan")
        acts("Love Letters") shouldContainExactly listOf("Luise Wolfram", "Jasna Fritzi Bauer")
        acts("Ewald Arenz") shouldContainExactly listOf("Ewald Arenz")
        acts("Constantin Schreiber") shouldContainExactly listOf("Constantin Schreiber")
        acts("Dinner for One").shouldBeEmpty()
        acts("Comedy-Concert").shouldBeEmpty()
        acts("Barbaren Barbies").shouldBeEmpty()
    }

    @Test
    fun `strips the status marks before reading the author`() {
        val horx = events.first { it.title.startsWith("Tristan Horx") }
        horx.status shouldBe "CANCELLED"
        horx.title shouldNotContain "+"
        horx.artists.map { it.name } shouldContainExactly listOf("Tristan Horx")
    }
}
