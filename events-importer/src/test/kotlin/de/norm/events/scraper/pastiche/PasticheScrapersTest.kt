package de.norm.events.scraper.pastiche

import de.norm.events.event.ArtistRole
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class PasticheScrapersTest {
    private val sourceUrl = "https://pasticheinternational.com/programme-1"
    private val ramziUrl = "https://pasticheinternational.com/ramzi-i-live-i-22-10"

    private val events by lazy { scrape(fixture("pastiche-programme.html")) }

    @Test
    fun `reads the month's nights and drops the workshop and the record fair`() {
        events.map { it.title } shouldContainExactly listOf("RAMZi", "megiapa")
        events.map { it.eventDate } shouldContainExactly listOf(LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 27))
    }

    @Test
    fun `maps a live set fully`() {
        val ramzi = events.first()

        ramzi.subtitle shouldBe "Live Set"
        ramzi.eventType shouldBe "CONCERT"
        ramzi.typeIsFallback shouldBe false
        ramzi.startTime shouldBe LocalTime.of(21, 0)
        ramzi.endDate shouldBe LocalDate.of(2026, 10, 22)
        ramzi.endTime shouldBe LocalTime.of(23, 0)
        ramzi.sourceUrl shouldBe ramziUrl
        ramzi.ticketUrl shouldBe ramziUrl
        ramzi.sourceId shouldBe "pastiche:ramzi-i-live-i-22-10"
        ramzi.description shouldBe "Hyped to host Montreal based mind-boggling all -genre-of-electronica producer Ramzi for a special live set."
        ramzi.artists shouldContainExactly listOf(ScrapedArtist("RAMZi", ArtistRole.HEADLINER))
        ramzi.free shouldBe false
    }

    @Test
    fun `keeps the paragraphs of a row's text`() {
        events[1].description shouldBe "International Anthem presents Megiapa with her new album.\nOne-off Berlin live performance"
    }

    @Test
    fun `infers the year from the printed weekday across the turn of the year`() {
        val scraped = scrape(row("Fri, 1st Jan", "Someone", "Live"), at = "2026-12-20T10:00:00Z").single()

        scraped.eventDate shouldBe LocalDate.of(2027, 1, 1)
        scraped.endDate.shouldBeNull()
        scraped.sourceUrl shouldBe sourceUrl
        scraped.sourceId shouldBe "pastiche:2027-01-01-someone"
    }

    @Test
    fun `skips a row whose date cannot be read`() {
        scrape(row("Soon", "Someone", "Live")).shouldBeEmpty()
        scrape(row("Fri, 31st Feb", "Someone", "Live")).shouldBeEmpty()
    }

    @Test
    fun `types a night by its format line and falls back to a concert`() {
        scrape(row("Sat, 24th Oct", "Pub Quiz", "with prizes")).single().eventType shouldBe "QUIZ"
        scrape(row("Sat, 24th Oct", "Back to Mine", "Book Talk")).single().eventType shouldBe "READING"
        val untyped = scrape(row("Sat, 24th Oct", "Ambient Sunday", null)).single()
        untyped.eventType shouldBe "CONCERT"
        untyped.typeIsFallback shouldBe true
        untyped.artists.shouldBeEmpty()
        scrape(row("Sat, 24th Oct", "Someone", "DJ Set")).single().artists shouldContainExactly listOf(ScrapedArtist("Someone", ArtistRole.DJ))
    }

    @Test
    fun `reads a free entry button`() {
        val html = row("Sat, 24th Oct", "Someone", "Live", button = """<a href="someone-i-24-10" rel="history"><b>FREE</b></a>""")
        val scraped = scrape(html).single()

        scraped.free shouldBe true
        scraped.ticketUrl.shouldBeNull()
        scraped.sourceUrl shouldBe "https://pasticheinternational.com/someone-i-24-10"
    }

    @Test
    fun `adds the poster and the full text from the event page`() {
        val enriched =
            PasticheEventPageScraper().enrich(listing(), Jsoup.parse(fixture("pastiche-ramzi.html"), ramziUrl)) ?: error("no enrichment")

        enriched.imageUrl shouldBe "https://freight.cargo.site/w/1200/i/M3166503320842509328530710884728/RAMZi-digi-3.jpeg"
        val description = enriched.description ?: error("no description")
        description shouldStartWith "RAMZi is the solo project of musician, DJ, and multidisciplinary artist Phoebé Guillemot from Montreal/ Tiohtià:ke."
        description shouldEndWith "Stasis, Mood Hut, RVNG Intl., 12th Isle, and Music From Memory."
        description.lines().size shouldBe 2
    }

    @Test
    fun `returns nothing for an event page without content`() {
        PasticheEventPageScraper().enrich(listing(), Jsoup.parse("<html><body></body></html>", ramziUrl)).shouldBeNull()
    }

    private fun listing() = events.first().copy(description = "short")

    private fun row(
        date: String,
        title: String,
        format: String?,
        button: String = ""
    ) = """
        <div class="pages"><div class="page"><bodycopy><column-set>
        <column-unit slot="0" span="3"><span>$date</span><br><br><span>20:00</span></column-unit>
        <column-unit slot="1" span="5"><span><b>$title</b><i><br><span>${format.orEmpty()}*</span></i></span></column-unit>
        <column-unit slot="2" span="4"><span>Text.</span><br>$button</column-unit>
        </column-set></bodycopy></div></div>
    """

    private fun scrape(
        html: String,
        at: String = "2026-10-17T10:00:00Z"
    ): List<ScrapedEvent> =
        PasticheProgrammePageScraper(Clock.fixed(Instant.parse(at), ZoneId.of("Europe/Berlin"))).scrape(Jsoup.parse(html, sourceUrl), sourceUrl)

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/pastiche/$name")!!
            .bufferedReader()
            .readText()
}
